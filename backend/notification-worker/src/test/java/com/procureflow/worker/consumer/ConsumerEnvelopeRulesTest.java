package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.events.RequestEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

/**
 * Pure consumer validation rules: legal transitions, transport identity, semantic canonical
 * comparison, independent recipient-table comparison, membership eligibility and fingerprint
 * stability. No services are involved.
 */
class ConsumerEnvelopeRulesTest {
    private final ConsumerEnvelopeParser parser = new ConsumerEnvelopeParser();

    @Test
    void acceptsExactlyTheProducerTransitions() {
        record Case(String type, String from, String to) { }
        List<Case> legal = List.of(
                new Case("request.submitted", "DRAFT", "AWAITING_DEPARTMENT_APPROVAL"),
                new Case("request.department_approved", "AWAITING_DEPARTMENT_APPROVAL", "AWAITING_FINANCE_APPROVAL"),
                new Case("request.approved", "AWAITING_DEPARTMENT_APPROVAL", "APPROVED"),
                new Case("request.approved", "AWAITING_FINANCE_APPROVAL", "APPROVED"),
                new Case("request.rejected", "AWAITING_DEPARTMENT_APPROVAL", "REJECTED"),
                new Case("request.rejected", "AWAITING_FINANCE_APPROVAL", "REJECTED"),
                new Case("request.cancelled", "AWAITING_DEPARTMENT_APPROVAL", "CANCELLED"),
                new Case("request.cancelled", "AWAITING_FINANCE_APPROVAL", "CANCELLED"));
        for (Case legalCase : legal) {
            assertThat(ConsumerEnvelopeRules.legalTransition(legalCase.type(),
                    new RequestEvent.Transition(legalCase.from(), legalCase.to(), 1, UUID.randomUUID())))
                    .as(legalCase.toString()).isTrue();
        }
        List<Case> illegal = List.of(
                new Case("request.submitted", "DRAFT", "APPROVED"),
                new Case("request.submitted", "DRAFT", "CANCELLED"),
                new Case("request.department_approved", "DRAFT", "AWAITING_FINANCE_APPROVAL"),
                new Case("request.approved", "DRAFT", "APPROVED"),
                new Case("request.rejected", "DRAFT", "REJECTED"),
                new Case("request.cancelled", "DRAFT", "CANCELLED"),
                new Case("request.approved", "APPROVED", "APPROVED"));
        for (Case illegalCase : illegal) {
            assertThat(ConsumerEnvelopeRules.legalTransition(illegalCase.type(),
                    new RequestEvent.Transition(illegalCase.from(), illegalCase.to(), 1, UUID.randomUUID())))
                    .as(illegalCase.toString()).isFalse();
        }
        assertThat(ConsumerEnvelopeRules.supportedType("request.exploded")).isFalse();
        assertThat(ConsumerEnvelopeRules.supportedType("request.submitted")).isTrue();
    }

    @Test
    void transportIdentityMustAgreeWithTheEnvelopeClaim() {
        var envelope = parse(ConsumerEnvelopes.valid());
        var properties = transport(envelope);

        assertThat(ConsumerEnvelopeRules.transportMatches(properties, envelope)).isTrue();
        properties.setMessageId(UUID.randomUUID().toString());
        assertThat(ConsumerEnvelopeRules.transportMatches(properties, envelope)).isFalse();

        var missingMessageId = transport(envelope);
        missingMessageId.setMessageId(null);
        assertThat(ConsumerEnvelopeRules.transportMatches(missingMessageId, envelope)).isFalse();

        var wrongType = transport(envelope);
        wrongType.setType("request.rejected");
        assertThat(ConsumerEnvelopeRules.transportMatches(wrongType, envelope)).isFalse();

        var wrongCorrelation = transport(envelope);
        wrongCorrelation.setCorrelationId(UUID.randomUUID().toString());
        assertThat(ConsumerEnvelopeRules.transportMatches(wrongCorrelation, envelope)).isFalse();

        var wrongVersion = transport(envelope);
        wrongVersion.setHeader("schemaVersion", 2);
        assertThat(ConsumerEnvelopeRules.transportMatches(wrongVersion, envelope)).isFalse();

        var missingVersion = transport(envelope);
        missingVersion.getHeaders().remove("schemaVersion");
        assertThat(ConsumerEnvelopeRules.transportMatches(missingVersion, envelope)).isFalse();
    }

    @Test
    void schemaVersionHeaderMustBeAnIntegralValueInTheIntRange() {
        var envelope = parse(ConsumerEnvelopes.valid());

        for (Object integral : List.<Object>of(1, 1L, (short) 1, (byte) 1)) {
            var properties = transport(envelope);
            properties.setHeader("schemaVersion", integral);
            assertThat(ConsumerEnvelopeRules.transportMatches(properties, envelope))
                    .as("integral header %s", integral).isTrue();
        }
        // A fractional value (1.5), an overflowed long (2^32+1) and non-numeric types must never be
        // truncated or coerced into the accepted version; even an integral-valued double is rejected
        // because the wire contract is an integral header type, not any Number.
        for (Object rejected : List.<Object>of(2, 4_294_967_297L, 1.5d, 1.0d, "1", true)) {
            var properties = transport(envelope);
            properties.setHeader("schemaVersion", rejected);
            assertThat(ConsumerEnvelopeRules.transportMatches(properties, envelope))
                    .as("nonconforming header %s", rejected).isFalse();
        }
    }

    @Test
    void canonicalComparisonIsSemanticButExactAboutIdentityTimeAndChannels() {
        var canonical = parse(ConsumerEnvelopes.valid());

        String reformatted = ConsumerEnvelopes.valid().replace("\":", "\" : ")
                .replace("},", "} ,\n");
        assertThat(ConsumerEnvelopeRules.matchesCanonical(parse(reformatted), canonical)).isTrue();

        String reorderedRecipients = ConsumerEnvelopes.json(ConsumerEnvelopes.EVENT, 1, "request.submitted",
                ConsumerEnvelopes.COMPANY, ConsumerEnvelopes.REQUEST, ConsumerEnvelopes.CORRELATION,
                ConsumerEnvelopes.OCCURRED_AT, "DRAFT", "AWAITING_DEPARTMENT_APPROVAL", 1,
                ConsumerEnvelopes.ACTOR, List.of(
                        new ConsumerEnvelopes.Recipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                                List.of("EMAIL", "IN_APP"))));
        assertThat(ConsumerEnvelopeRules.matchesCanonical(parse(reorderedRecipients), canonical)).isTrue();

        String forgedActor = ConsumerEnvelopes.valid().replace(ConsumerEnvelopes.ACTOR.toString(),
                UUID.randomUUID().toString());
        assertThat(ConsumerEnvelopeRules.matchesCanonical(parse(forgedActor), canonical)).isFalse();

        String shiftedTime = ConsumerEnvelopes.valid().replace(ConsumerEnvelopes.OCCURRED_AT.toString(),
                ConsumerEnvelopes.OCCURRED_AT.plusSeconds(1).toString());
        assertThat(ConsumerEnvelopeRules.matchesCanonical(parse(shiftedTime), canonical)).isFalse();

        String forgedVersion = ConsumerEnvelopes.valid().replace("\"requestVersion\":1",
                "\"requestVersion\":2");
        assertThat(ConsumerEnvelopeRules.matchesCanonical(parse(forgedVersion), canonical)).isFalse();

        String forgedChannels = ConsumerEnvelopes.valid()
                .replace("[\"IN_APP\",\"EMAIL\"]", "[\"IN_APP\"]");
        assertThat(ConsumerEnvelopeRules.matchesCanonical(parse(forgedChannels), canonical)).isFalse();

        String extraRecipient = ConsumerEnvelopes.json(ConsumerEnvelopes.EVENT, 1, "request.submitted",
                ConsumerEnvelopes.COMPANY, ConsumerEnvelopes.REQUEST, ConsumerEnvelopes.CORRELATION,
                ConsumerEnvelopes.OCCURRED_AT, "DRAFT", "AWAITING_DEPARTMENT_APPROVAL", 1,
                ConsumerEnvelopes.ACTOR, List.of(
                        new ConsumerEnvelopes.Recipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                                List.of("IN_APP", "EMAIL")),
                        new ConsumerEnvelopes.Recipient(UUID.randomUUID(), UUID.randomUUID(),
                                List.of("EMAIL"))));
        assertThat(ConsumerEnvelopeRules.matchesCanonical(parse(extraRecipient), canonical)).isFalse();
    }

    @Test
    void recipientTableMustMatchTheCanonicalEnvelopeKeysAndChannels() {
        var canonical = parse(ConsumerEnvelopes.json(ConsumerEnvelopes.EVENT, 1, "request.submitted",
                ConsumerEnvelopes.COMPANY, ConsumerEnvelopes.REQUEST, ConsumerEnvelopes.CORRELATION,
                ConsumerEnvelopes.OCCURRED_AT, "DRAFT", "AWAITING_DEPARTMENT_APPROVAL", 1,
                ConsumerEnvelopes.ACTOR, List.of(
                        new ConsumerEnvelopes.Recipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                                List.of("IN_APP", "EMAIL")),
                        new ConsumerEnvelopes.Recipient(UUID.randomUUID(), UUID.randomUUID(),
                                List.of("EMAIL")))));

        assertThat(ConsumerEnvelopeRules.recipientTableMatches(canonical, List.of(
                new CanonicalEvent.CanonicalRecipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                        true, true),
                new CanonicalEvent.CanonicalRecipient(canonical.recipients().get(1).identityId(),
                        canonical.recipients().get(1).membershipId(), false, true)))).isTrue();

        assertThat(ConsumerEnvelopeRules.recipientTableMatches(canonical, List.of(
                new CanonicalEvent.CanonicalRecipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                        true, false),
                new CanonicalEvent.CanonicalRecipient(canonical.recipients().get(1).identityId(),
                        canonical.recipients().get(1).membershipId(), false, true))))
                .as("channel booleans must mirror the envelope channel set").isFalse();

        assertThat(ConsumerEnvelopeRules.recipientTableMatches(canonical, List.of(
                new CanonicalEvent.CanonicalRecipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                        true, true)))).as("a missing recipient row must be detected").isFalse();

        assertThat(ConsumerEnvelopeRules.recipientTableMatches(canonical, List.of(
                new CanonicalEvent.CanonicalRecipient(ConsumerEnvelopes.IDENTITY, UUID.randomUUID(), true, true),
                new CanonicalEvent.CanonicalRecipient(canonical.recipients().get(1).identityId(),
                        canonical.recipients().get(1).membershipId(), false, true))))
                .as("a different membership key must be detected").isFalse();
    }

    @Test
    void membershipRevalidationUsesFixedOrderedReasons() {
        var recipient = new RequestEvent.Recipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                List.of(RequestEvent.Channel.IN_APP));
        assertThat(MembershipEligibility.revalidate(ConsumerEnvelopes.COMPANY, recipient, null))
                .isEqualTo(MembershipEligibility.MISSING);
        assertThat(MembershipEligibility.revalidate(ConsumerEnvelopes.COMPANY, recipient,
                new MembershipEligibility.MembershipState(ConsumerEnvelopes.MEMBERSHIP, UUID.randomUUID(),
                        ConsumerEnvelopes.IDENTITY, true))).isEqualTo(MembershipEligibility.COMPANY_MISMATCH);
        assertThat(MembershipEligibility.revalidate(ConsumerEnvelopes.COMPANY, recipient,
                new MembershipEligibility.MembershipState(ConsumerEnvelopes.MEMBERSHIP, ConsumerEnvelopes.COMPANY,
                        UUID.randomUUID(), true))).isEqualTo(MembershipEligibility.IDENTITY_MISMATCH);
        assertThat(MembershipEligibility.revalidate(ConsumerEnvelopes.COMPANY, recipient,
                new MembershipEligibility.MembershipState(ConsumerEnvelopes.MEMBERSHIP, ConsumerEnvelopes.COMPANY,
                        ConsumerEnvelopes.IDENTITY, false))).isEqualTo(MembershipEligibility.INACTIVE);
        assertThat(MembershipEligibility.revalidate(ConsumerEnvelopes.COMPANY, recipient,
                new MembershipEligibility.MembershipState(ConsumerEnvelopes.MEMBERSHIP, ConsumerEnvelopes.COMPANY,
                        ConsumerEnvelopes.IDENTITY, true))).isNull();
    }

    @Test
    void fingerprintsAreStableBoundedAndCodeScoped() {
        String eventFingerprint = ConsumerFailureFingerprint.forEvent(ConsumerEnvelopes.COMPANY,
                ConsumerEnvelopes.EVENT, ConsumerErrorCode.CANONICAL_ENVELOPE_MISMATCH);
        assertThat(eventFingerprint).matches("^[0-9a-f]{64}$");
        assertThat(ConsumerFailureFingerprint.forEvent(ConsumerEnvelopes.COMPANY, ConsumerEnvelopes.EVENT,
                ConsumerErrorCode.CANONICAL_ENVELOPE_MISMATCH)).isEqualTo(eventFingerprint);
        assertThat(ConsumerFailureFingerprint.forEvent(ConsumerEnvelopes.COMPANY, ConsumerEnvelopes.EVENT,
                ConsumerErrorCode.INVALID_TRANSITION)).isNotEqualTo(eventFingerprint);
        assertThat(ConsumerFailureFingerprint.forEvent(ConsumerEnvelopes.COMPANY, UUID.randomUUID(),
                ConsumerErrorCode.CANONICAL_ENVELOPE_MISMATCH)).isNotEqualTo(eventFingerprint);

        byte[] body = ConsumerEnvelopes.valid().getBytes(StandardCharsets.UTF_8);
        String raw = ConsumerFailureFingerprint.forRawBody(body, ConsumerErrorCode.MALFORMED_MESSAGE);
        assertThat(raw).matches("^[0-9a-f]{64}$");
        assertThat(ConsumerFailureFingerprint.forRawBody(body.clone(), ConsumerErrorCode.MALFORMED_MESSAGE))
                .isEqualTo(raw);
        assertThat(ConsumerFailureFingerprint.forRawBody(
                (ConsumerEnvelopes.valid() + " ").getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.MALFORMED_MESSAGE))
                .as("unknown raw input is hashed byte-exactly; documented limitation").isNotEqualTo(raw);
        assertThat(ConsumerFailureFingerprint.forRawBody(body, ConsumerErrorCode.CANONICAL_EVENT_MISSING))
                .isNotEqualTo(raw);
    }

    private ConsumerEnvelope parse(String json) {
        var result = parser.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(result.valid()).as(json).isTrue();
        return result.envelope();
    }

    private static MessageProperties transport(ConsumerEnvelope envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(envelope.eventId().toString());
        properties.setType(envelope.type());
        properties.setCorrelationId(envelope.correlationId().toString());
        properties.setHeader("schemaVersion", envelope.schemaVersion());
        return properties;
    }
}

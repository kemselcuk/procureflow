package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.events.RequestEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Strict schema-v1 parsing rules for untrusted input; no service fixtures are involved. */
class ConsumerEnvelopeParserTest {
    private final ConsumerEnvelopeParser parser = new ConsumerEnvelopeParser();

    @Test
    void parsesAValidEnvelopeIntoTypedValues() {
        var result = parse(ConsumerEnvelopes.valid());

        assertThat(result.valid()).isTrue();
        var envelope = result.envelope();
        assertThat(envelope.eventId()).isEqualTo(ConsumerEnvelopes.EVENT);
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.type()).isEqualTo("request.submitted");
        assertThat(envelope.companyId()).isEqualTo(ConsumerEnvelopes.COMPANY);
        assertThat(envelope.requestId()).isEqualTo(ConsumerEnvelopes.REQUEST);
        assertThat(envelope.correlationId()).isEqualTo(ConsumerEnvelopes.CORRELATION);
        assertThat(envelope.occurredAt()).isEqualTo(ConsumerEnvelopes.OCCURRED_AT);
        assertThat(envelope.transition()).isEqualTo(new RequestEvent.Transition("DRAFT",
                "AWAITING_DEPARTMENT_APPROVAL", 1, ConsumerEnvelopes.ACTOR));
        assertThat(envelope.recipients()).singleElement().satisfies(recipient -> {
            assertThat(recipient.identityId()).isEqualTo(ConsumerEnvelopes.IDENTITY);
            assertThat(recipient.membershipId()).isEqualTo(ConsumerEnvelopes.MEMBERSHIP);
            assertThat(recipient.channels()).containsExactly(RequestEvent.Channel.IN_APP,
                    RequestEvent.Channel.EMAIL);
        });
    }

    @Test
    void normalizesUuidCaseAndAcceptsAnEmptyRecipientList() {
        String upperCase = ConsumerEnvelopes.valid().replace(
                ConsumerEnvelopes.EVENT.toString(), ConsumerEnvelopes.EVENT.toString().toUpperCase());
        var envelope = parse(upperCase).envelope();
        assertThat(envelope.eventId()).isEqualTo(ConsumerEnvelopes.EVENT);

        String empty = ConsumerEnvelopes.json(ConsumerEnvelopes.EVENT, 1, "request.submitted",
                ConsumerEnvelopes.COMPANY, ConsumerEnvelopes.REQUEST, ConsumerEnvelopes.CORRELATION,
                ConsumerEnvelopes.OCCURRED_AT, "DRAFT", "AWAITING_DEPARTMENT_APPROVAL", 1,
                ConsumerEnvelopes.ACTOR, List.of());
        assertThat(parse(empty).valid()).isTrue();
        assertThat(parse(empty).envelope().recipients()).isEmpty();
    }

    @Test
    void rejectsUnknownMissingAndWronglyTypedFields() {
        String extraTopLevel = ConsumerEnvelopes.valid().replace("\"recipients\":",
                "\"unknown\":1,\"recipients\":");
        String extraPayload = ConsumerEnvelopes.valid().replace("\"actorIdentityId\":",
                "\"extra\":true,\"actorIdentityId\":");
        String missingOccurredAt = ConsumerEnvelopes.valid()
                .replace("\"occurredAt\":\"" + ConsumerEnvelopes.OCCURRED_AT + "\",", "");
        String numericEventId = ConsumerEnvelopes.valid().replace(
                "\"eventId\":\"" + ConsumerEnvelopes.EVENT + "\"", "\"eventId\":42");
        String hugeSchemaVersion = ConsumerEnvelopes.valid().replace("\"schemaVersion\":1",
                "\"schemaVersion\":4294967297");
        String nonObject = "[1,2,3]";

        for (String invalid : List.of(extraTopLevel, extraPayload, missingOccurredAt, numericEventId,
                hugeSchemaVersion, nonObject)) {
            assertThat(parse(invalid).valid()).as(invalid).isFalse();
        }
    }

    @Test
    void rejectsMalformedJsonAndOversizedShapesBeforeTheyCanBeTrusted() {
        assertThat(parse("{").valid()).isFalse();
        assertThat(parse("").valid()).isFalse();
        assertThat(parse("\u0000\u0001").valid()).isFalse();
    }

    @Test
    void rejectsDuplicateIdentitiesChannelsAndUnknownChannels() {
        String duplicateIdentity = ConsumerEnvelopes.json(ConsumerEnvelopes.EVENT, 1, "request.submitted",
                ConsumerEnvelopes.COMPANY, ConsumerEnvelopes.REQUEST, ConsumerEnvelopes.CORRELATION,
                ConsumerEnvelopes.OCCURRED_AT, "DRAFT", "AWAITING_DEPARTMENT_APPROVAL", 1,
                ConsumerEnvelopes.ACTOR, List.of(
                        new ConsumerEnvelopes.Recipient(ConsumerEnvelopes.IDENTITY, ConsumerEnvelopes.MEMBERSHIP,
                                List.of("IN_APP")),
                        new ConsumerEnvelopes.Recipient(ConsumerEnvelopes.IDENTITY, UUID.randomUUID(),
                                List.of("EMAIL"))));
        String duplicateChannel = ConsumerEnvelopes.valid()
                .replace("[\"IN_APP\",\"EMAIL\"]", "[\"IN_APP\",\"IN_APP\"]");
        String unknownChannel = ConsumerEnvelopes.valid()
                .replace("[\"IN_APP\",\"EMAIL\"]", "[\"SMS\"]");
        String emptyChannels = ConsumerEnvelopes.valid().replace("[\"IN_APP\",\"EMAIL\"]", "[]");
        String lowercaseChannel = ConsumerEnvelopes.valid().replace("\"IN_APP\"", "\"in_app\"");

        for (String invalid : List.of(duplicateIdentity, duplicateChannel, unknownChannel, emptyChannels,
                lowercaseChannel)) {
            assertThat(parse(invalid).valid()).as(invalid).isFalse();
        }
    }

    @Test
    void rejectsDuplicateObjectKeysAndTrailingRootContent() {
        // A lenient "last duplicate key wins" reader could resolve a different value than the first
        // one visible to a human reviewer; duplicates are rejected anywhere in the object tree.
        String duplicateTopLevel = ConsumerEnvelopes.valid().replace("\"schemaVersion\":1,",
                "\"schemaVersion\":1,\"schemaVersion\":2,");
        String duplicatePayload = ConsumerEnvelopes.valid().replace(
                "\"toStatus\":\"AWAITING_DEPARTMENT_APPROVAL\",",
                "\"toStatus\":\"AWAITING_DEPARTMENT_APPROVAL\",\"toStatus\":\"APPROVED\",");
        String duplicateRecipient = ConsumerEnvelopes.valid().replace("\"channels\":[\"IN_APP\",\"EMAIL\"]",
                "\"channels\":[\"IN_APP\",\"EMAIL\"],\"channels\":[\"EMAIL\"]");
        String trailingRoot = ConsumerEnvelopes.valid() + "{\"extra\":1}";
        String trailingGarbage = ConsumerEnvelopes.valid() + " extra";

        for (String invalid : List.of(duplicateTopLevel, duplicatePayload, duplicateRecipient, trailingRoot,
                trailingGarbage)) {
            assertThat(parse(invalid).valid()).as(invalid).isFalse();
        }
    }

    private ConsumerEnvelopeParser.Result parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }
}

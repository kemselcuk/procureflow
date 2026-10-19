package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.ConsumerServiceFixture.RecipientSeed;
import com.procureflow.worker.support.RelayServiceFixture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Real-service quarantine evidence: malformed, oversized, forged transport, forged envelope and
 * unknown-event deliveries are acknowledged only after a sanitized durable diagnostic record, never
 * materialize, and never leak a raw body. Fingerprints are stable for known canonical events even
 * across whitespace/key-order variants, and valid traffic keeps progressing afterwards.
 */
class ConsumerQuarantineIT extends ConsumerIntegrationTestBase {

    @Test
    void malformedBodyIsQuarantinedWithAStableRawFingerprintAndNoEffects() {
        byte[] body = ("{\"eventId\":\"not-a-uuid\",\"schemaVersion\":1,").getBytes(StandardCharsets.UTF_8);
        String hash = ConsumerFailureFingerprint.forRawBody(body, ConsumerErrorCode.MALFORMED_MESSAGE);

        publishRaw(new String(body, StandardCharsets.UTF_8));
        await("raw quarantine record", () -> ConsumerServiceFixture.diagnostic(hash).isPresent());
        await("acknowledged poisoned delivery", () -> ConsumerServiceFixture.queueDepth().drained());

        var row = ConsumerServiceFixture.diagnostic(hash).orElseThrow();
        assertThat(row.payloadHash()).matches("^[0-9a-f]{64}$");
        assertThat(row.errorCode()).isEqualTo("malformed_message");
        assertThat(row.channel()).isEqualTo("IN_APP");
        assertThat(row.companyId()).isNull();
        assertThat(row.eventId()).isNull();
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(ConsumerServiceFixture.total("notification_inbox")).isZero();
        assertThat(ConsumerServiceFixture.total("notification_delivery")).isZero();

        publishRaw(new String(body, StandardCharsets.UTF_8));
        await("observed repeated raw poison",
                () -> ConsumerServiceFixture.diagnostic(hash).orElseThrow().attempts() == 2);
        await("repeated poison acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertThat(ConsumerServiceFixture.diagnosticsWithoutScope()).hasSize(1);
    }

    @Test
    void oversizedJsonIsRejectedBeforeItCanMaterialize() {
        var actor = ConsumerServiceFixture.createActor("oversize");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String padded = seeded.envelope() + " ".repeat(properties.maxBodyBytes());
        assertThat(padded.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(properties.maxBodyBytes());
        String hash = ConsumerFailureFingerprint.forRawBody(padded.getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.MALFORMED_MESSAGE);

        publishRaw(padded);
        await("oversize quarantine record", () -> ConsumerServiceFixture.diagnostic(hash).isPresent());
        await("oversize delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertThat(ConsumerServiceFixture.diagnostic(hash).orElseThrow().errorCode())
                .isEqualTo("malformed_message");
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();

        publish(seeded);
        await("the same unpadded event still materializes",
                () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("valid delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isEqualTo(1);
    }

    @Test
    void forgedTransportIdentityVariantsShareOneEventScopedQuarantineRecord() {
        var actor = ConsumerServiceFixture.createActor("transport");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String hash = ConsumerFailureFingerprint.forEvent(seeded.company(), seeded.event(),
                ConsumerErrorCode.TRANSPORT_IDENTITY_MISMATCH);

        publish(seeded.envelope(), UUID.randomUUID(), seeded.type(), seeded.correlation(), 1);
        await("messageId mismatch record", () -> attempts(seeded) == 1);
        publish(seeded.envelope(), seeded.event(), "request.rejected", seeded.correlation(), 1);
        await("type mismatch observed on the same record", () -> attempts(seeded) == 2);
        publish(seeded.envelope(), seeded.event(), seeded.type(), UUID.randomUUID(), 1);
        await("correlation mismatch observed on the same record", () -> attempts(seeded) == 3);
        publish(seeded.envelope(), seeded.event(), seeded.type(), seeded.correlation(), 2);
        await("schema header mismatch observed on the same record", () -> attempts(seeded) == 4);
        await("forged deliveries acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        var rows = ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.payloadHash()).isEqualTo(hash);
            assertThat(row.errorCode()).isEqualTo("transport_identity_mismatch");
            assertThat(row.attempts()).isEqualTo(4);
        });
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
    }

    @Test
    void forgedSchemaTypeTransitionAndEnvelopeVariantsGetFixedEventScopedCodes() {
        var actor = ConsumerServiceFixture.createActor("forged");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var extra = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var canonicalRecipient = RecipientSeed.both(member);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted", List.of(canonicalRecipient));

        String schemaTwo = seeded.envelope().replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        publish(schemaTwo, seeded.event(), seeded.type(), seeded.correlation(), 2);
        await("schema quarantine", () -> hasCode(seeded, "unsupported_schema_version"));

        String unknownType = seeded.envelope()
                .replace("\"type\":\"request.submitted\"", "\"type\":\"request.exploded\"");
        publish(unknownType, seeded.event(), "request.exploded", seeded.correlation(), 1);
        await("type quarantine", () -> hasCode(seeded, "unsupported_event_type"));

        String illegalTransition = seeded.envelope().replace(
                "\"fromStatus\":\"DRAFT\",\"toStatus\":\"AWAITING_DEPARTMENT_APPROVAL\"",
                "\"fromStatus\":\"APPROVED\",\"toStatus\":\"DRAFT\"");
        publish(illegalTransition, seeded.event(), seeded.type(), seeded.correlation(), 1);
        await("transition quarantine", () -> hasCode(seeded, "invalid_transition"));

        String forgedActor = seeded.envelope().replace("\"actorIdentityId\":\"" + seeded.actorIdentity() + "\"",
                "\"actorIdentityId\":\"" + UUID.randomUUID() + "\"");
        publish(forgedActor, seeded.event(), seeded.type(), seeded.correlation(), 1);
        await("payload quarantine", () -> mismatchAttempts(seeded) == 1);

        // Same forged content, different formatting/key separators: one stable canonical fingerprint.
        String whitespaceVariant = forgedActor.replace("\":", "\" : ");
        assertThat(whitespaceVariant).isNotEqualTo(forgedActor);
        publish(whitespaceVariant, seeded.event(), seeded.type(), seeded.correlation(), 1);
        await("whitespace variant observed on the same canonical record", () -> mismatchAttempts(seeded) == 2);

        String forgedChannel = seeded.envelope()
                .replace("\"channels\":[\"IN_APP\",\"EMAIL\"]", "\"channels\":[\"EMAIL\"]");
        publish(forgedChannel, seeded.event(), seeded.type(), seeded.correlation(), 1);
        await("channel quarantine", () -> mismatchAttempts(seeded) == 3);

        String extraRecipient = ConsumerServiceFixture.envelope(seeded.event(), seeded.type(), seeded.company(),
                seeded.request(), seeded.correlation(), seeded.occurredAt(), seeded.fromStatus(), seeded.toStatus(),
                1, seeded.actorIdentity(), List.of(canonicalRecipient, RecipientSeed.both(extra)));
        publish(extraRecipient, seeded.event(), seeded.type(), seeded.correlation(), 1);
        await("recipient quarantine", () -> mismatchAttempts(seeded) == 4);
        await("all forged deliveries acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        Map<String, Integer> attemptsByCode = ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())
                .stream().collect(Collectors.toMap(ConsumerServiceFixture.DiagnosticRow::errorCode,
                        ConsumerServiceFixture.DiagnosticRow::attempts));
        assertThat(attemptsByCode).containsEntry("unsupported_schema_version", 1)
                .containsEntry("unsupported_event_type", 1)
                .containsEntry("invalid_transition", 1)
                .containsEntry("canonical_envelope_mismatch", 4);
        assertThat(attemptsByCode.keySet()).doesNotContain("canonical_event_missing",
                "recipient_snapshot_mismatch", "transport_identity_mismatch", "materialization_failed");
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
    }

    @Test
    void unknownCanonicalEventIsQuarantinedWithoutCompanyOrEventScope() {
        UUID company = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String envelope = ConsumerServiceFixture.envelope(event, "request.submitted", company, UUID.randomUUID(),
                correlation, java.time.Instant.now(), "DRAFT", "AWAITING_DEPARTMENT_APPROVAL", 1,
                UUID.randomUUID(), List.of(new RecipientSeed(identity, membership, true, true)));
        String hash = ConsumerFailureFingerprint.forRawBody(envelope.getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.CANONICAL_EVENT_MISSING);

        publish(envelope, event, "request.submitted", correlation, 1);
        await("missing canonical event record", () -> ConsumerServiceFixture.diagnostic(hash).isPresent());
        await("unknown event delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        var row = ConsumerServiceFixture.diagnostic(hash).orElseThrow();
        assertThat(row.errorCode()).isEqualTo("canonical_event_missing");
        assertThat(row.companyId()).isNull();
        assertThat(row.eventId()).isNull();
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(ConsumerServiceFixture.total("notification_inbox")).isZero();
        assertThat(ConsumerServiceFixture.deliveries(company, event)).isEmpty();
    }

    @Test
    void quarantineRowsNeverContainTheRawBody() {
        String marker = "SECRET-MARKER-" + UUID.randomUUID();
        byte[] body = ("{\"freeText\":\"" + marker + "\",").getBytes(StandardCharsets.UTF_8);

        publishRaw(new String(body, StandardCharsets.UTF_8));
        await("quarantine record", () -> ConsumerServiceFixture.diagnosticsWithoutScope().size() == 1);
        await("quarantined delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        Long leaked = RelayServiceFixture.worker().queryForObject("""
                SELECT count(*) FROM notification_diagnostic
                WHERE payload_hash ILIKE ? OR error_code ILIKE ? OR channel ILIKE ?
                """, Long.class, "%" + marker + "%", "%" + marker + "%", "%" + marker + "%");
        assertThat(leaked).as("no diagnostic column may contain message content").isZero();
        assertThat(ConsumerServiceFixture.total("notification_diagnostic")).isEqualTo(1);
    }

    @Test
    void validEventsStillProgressAfterSeveralQuarantinedPoisons() {
        publishRaw("{not-json");
        await("poison quarantined", () -> ConsumerServiceFixture.diagnosticsWithoutScope().size() == 1);
        await("poison acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        var actor = ConsumerServiceFixture.createActor("after-poison");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        publish(seeded);
        await("valid event after poison", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("valid event acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2);
    }

    private int attempts(ConsumerServiceFixture.SeededEvent seeded) {
        return ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).stream()
                .mapToInt(ConsumerServiceFixture.DiagnosticRow::attempts).sum();
    }

    private int mismatchAttempts(ConsumerServiceFixture.SeededEvent seeded) {
        return ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).stream()
                .filter(row -> row.errorCode().equals("canonical_envelope_mismatch"))
                .mapToInt(ConsumerServiceFixture.DiagnosticRow::attempts).sum();
    }

    private boolean hasCode(ConsumerServiceFixture.SeededEvent seeded, String code) {
        return ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).stream()
                .anyMatch(row -> row.errorCode().equals(code));
    }
}

package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procureflow.worker.config.RabbitTopology;
import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.ConsumerServiceFixture.RecipientSeed;
import com.procureflow.worker.support.RelayServiceFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

/**
 * M1 regression on real PostgreSQL and RabbitMQ: a valid canonical message whose canonical snapshot
 * read fails is durably recorded under the raw transient fingerprint. The shared operator replay must
 * derive exactly that fingerprint from the selected immutable canonical envelope bytes (the relay
 * publishes {@code envelope::text}) and clear only the selected event's diagnostics plus that one raw
 * record. Unrelated raw, sibling-event and other-company diagnostics must survive.
 *
 * <p>The canonical event, its PUBLISHED dispatch row and the message queued through the real broker
 * are explicit component setup, not business-flow or live-relay proof. The SELECT privilege fault is
 * scoped to this fixture's worker login; the relay login shares that grant, so the test publishes the
 * already-queued canonical bytes instead of claiming an isolated consumer connection outage.
 */
class ConsumerCanonicalReadReplayIT extends ConsumerIntegrationTestBase {
    private static final String TRANSIENT_CODE = "materialization_failed";

    @Test
    void sharedScopedReplayRecoversOnlyTheCanonicalRawTransientRecord() {
        var actor = ConsumerServiceFixture.createActor("raw-replay");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String canonicalBody = canonicalBody(seeded);
        byte[] canonicalBytes = canonicalBody.getBytes(StandardCharsets.UTF_8);
        String rawHash = ConsumerFailureFingerprint.forRawBody(canonicalBytes,
                ConsumerErrorCode.MATERIALIZATION_FAILED);
        markPublished(seeded);

        // A real malformed probe produces a raw diagnostic that the event-scoped replay must retain.
        publishRaw("{not-json");
        await("malformed raw diagnostic", () -> ConsumerServiceFixture.diagnosticsWithoutScope().stream()
                .anyMatch(row -> "malformed_message".equals(row.errorCode())));
        await("malformed probe acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        String malformedHash = ConsumerServiceFixture.diagnosticsWithoutScope().stream()
                .filter(row -> "malformed_message".equals(row.errorCode()))
                .findFirst().orElseThrow().payloadHash();

        var otherActor = ConsumerServiceFixture.createActor("other-company");
        var otherEvent = ConsumerServiceFixture.seedEvent(otherActor, "request.submitted", List.of());
        markPublished(otherEvent);
        String otherHash = ConsumerFailureFingerprint.forEvent(otherEvent.company(), otherEvent.event(),
                ConsumerErrorCode.MATERIALIZATION_FAILED);
        insertDiagnostic(otherHash, otherEvent.company(), otherEvent.event());

        var sibling = ConsumerServiceFixture.seedEvent(actor, "request.submitted", List.of());
        markPublished(sibling);
        String siblingHash = ConsumerFailureFingerprint.forEvent(sibling.company(), sibling.event(),
                ConsumerErrorCode.MATERIALIZATION_FAILED);
        insertDiagnostic(siblingHash, sibling.company(), sibling.event());

        // Queue the exact relay bytes before the fault; the listener is stopped so the delivery cannot
        // materialize before SELECT is revoked.
        stopListener();
        publish(canonicalBody, seeded.event(), seeded.type(), seeded.correlation(), 1);
        try {
            RelayServiceFixture.owner().execute("REVOKE SELECT ON outbox_event FROM procureflow_worker");
            startListener();
            await("terminal raw transient diagnostic", () -> ConsumerServiceFixture.diagnostic(rawHash)
                    .map(row -> row.attempts() == properties.maxAttempts()).orElse(false));
            await("raw transient delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

            var raw = ConsumerServiceFixture.diagnostic(rawHash).orElseThrow();
            assertThat(raw.errorCode()).isEqualTo(TRANSIENT_CODE);
            assertThat(raw.channel()).isEqualTo("IN_APP");
            assertThat(raw.attempts()).isEqualTo(properties.maxAttempts());
            assertThat(raw.companyId()).isNull();
            assertThat(raw.eventId()).isNull();
            assertThat(ConsumerServiceFixture.total("notification_inbox")).isZero();
            assertThat(ConsumerServiceFixture.total("notification_delivery")).isZero();
            assertThat(ConsumerServiceFixture.total("in_app_notification")).isZero();
            assertThat(ConsumerServiceFixture.total("email_job")).isZero();
        } finally {
            RelayServiceFixture.owner().execute("GRANT SELECT ON outbox_event TO procureflow_worker");
            stopListener();
        }

        assertThat(sqlFingerprint(seeded)).as("PostgreSQL sha256() matches the Java fingerprint over the "
                + "canonical UTF-8 bytes").isEqualTo(rawHash);

        // Fail closed on wrong company/event pairing, a live lease and a missing dispatch row.
        assertReplayRefused(otherEvent.company(), seeded.event());
        assertReplaySideEffectsUnchanged(seeded, rawHash, malformedHash, otherHash, siblingHash, "PUBLISHED");

        assertReplayRefused(actor.company(), UUID.randomUUID());
        assertReplaySideEffectsUnchanged(seeded, rawHash, malformedHash, otherHash, siblingHash, "PUBLISHED");

        RelayServiceFixture.owner().update("""
                UPDATE outbox_dispatch SET state = 'LEASED', published_at = NULL, lease_token = ?,
                lease_until = now() + interval '1 hour', error_code = NULL
                WHERE company_id = ? AND event_id = ?
                """, UUID.randomUUID(), seeded.company(), seeded.event());
        assertReplayRefused(actor.company(), seeded.event());
        assertReplaySideEffectsUnchanged(seeded, rawHash, malformedHash, otherHash, siblingHash, "LEASED");
        markPublished(seeded);

        executeReplay(actor.company(), seeded.event());

        var dispatch = dispatch(seeded.event());
        assertThat(dispatch.state()).isEqualTo("PENDING");
        assertThat(dispatch.attempts()).isZero();
        assertThat(dispatch.publishedAt()).isNull();
        assertThat(ConsumerServiceFixture.diagnostic(rawHash)).as("the exact raw transient counter is cleared")
                .isEmpty();
        assertThat(ConsumerServiceFixture.diagnostic(malformedHash))
                .as("an unrelated malformed raw record is retained").isPresent();
        assertThat(ConsumerServiceFixture.diagnostic(otherHash))
                .as("another company's event-scoped diagnostic is retained").isPresent();
        assertThat(ConsumerServiceFixture.diagnostic(siblingHash))
                .as("a sibling event's diagnostic is retained").isPresent();
        assertThat(ConsumerServiceFixture.diagnostics(actor.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();
        assertThat(outboxEventCount(seeded)).isEqualTo(1);
        assertThat(outboxRecipientCount(seeded)).isEqualTo(1);

        // Resume the real relay/consumer on the same broker and database without clearing either.
        startListener();
        relay.runOnce();
        await("recovered inbox materialization",
                () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("recovered delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event()))
                .isEqualTo(1);
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2)
                .allSatisfy(delivery -> {
                    assertThat(delivery.identityId()).isEqualTo(member.identity());
                    assertThat(delivery.membershipId()).isEqualTo(member.membership());
                    assertThat(delivery.outcome()).isEqualTo("MATERIALIZED");
                });
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).singleElement()
                .satisfies(notification -> assertThat(notification.identityId()).isEqualTo(member.identity()));
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).singleElement()
                .satisfies(job -> {
                    assertThat(job.identityId()).isEqualTo(member.identity());
                    assertThat(job.state()).isEqualTo("PENDING");
                });
        assertThat(dispatch(seeded.event()).state()).isEqualTo("PUBLISHED");
        assertThat(ConsumerServiceFixture.diagnostic(rawHash)).isEmpty();
        assertThat(ConsumerServiceFixture.diagnostics(actor.company(), seeded.event())).isEmpty();

        // A later duplicate redelivery of the same still-open event is suppressed and preserves reads.
        Instant readAt = ConsumerServiceFixture.markInAppRead(seeded.company(), member.identity());
        Instant sentAt = ConsumerServiceFixture.markEmailSent(seeded.company(), member.identity());
        var jobBefore = ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event()).get(0);
        long deliveredBefore = wireDeliveries();
        publish(canonicalBody, seeded.event(), seeded.type(), seeded.correlation(), 1);
        await("duplicate delivery observed on the wire", () -> wireDeliveries() > deliveredBefore);
        await("duplicate delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event()))
                .isEqualTo(1);
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2);
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).singleElement()
                .satisfies(notification -> assertThat(notification.readAt()).isEqualTo(readAt));
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).singleElement()
                .satisfies(job -> {
                    assertThat(job.id()).isEqualTo(jobBefore.id());
                    assertThat(job.state()).isEqualTo("SENT");
                    assertThat(job.sentAt()).isEqualTo(sentAt);
                });
        assertThat(ConsumerServiceFixture.diagnostics(actor.company(), seeded.event())).isEmpty();
    }

    private static String canonicalBody(ConsumerServiceFixture.SeededEvent seeded) {
        String body = RelayServiceFixture.owner().queryForObject(
                "SELECT envelope::text FROM outbox_event WHERE company_id = ? AND id = ?",
                String.class, seeded.company(), seeded.event());
        return java.util.Objects.requireNonNull(body, "the seeded canonical event is missing");
    }

    private static void markPublished(ConsumerServiceFixture.SeededEvent seeded) {
        int updated = RelayServiceFixture.owner().update("""
                UPDATE outbox_dispatch SET state = 'PUBLISHED', attempts = 1, published_at = now(),
                next_attempt_at = now(), lease_token = NULL, lease_until = NULL, error_code = NULL
                WHERE company_id = ? AND event_id = ?
                """, seeded.company(), seeded.event());
        assertThat(updated).as("component setup marks the seeded dispatch row published").isEqualTo(1);
    }

    private static void insertDiagnostic(String payloadHash, UUID company, UUID event) {
        RelayServiceFixture.owner().update("""
                INSERT INTO notification_diagnostic
                    (payload_hash, company_id, event_id, channel, error_code, attempts)
                VALUES (?, ?, ?, 'IN_APP', ?, 1)
                """, payloadHash, company, event, TRANSIENT_CODE);
    }

    private static String sqlFingerprint(ConsumerServiceFixture.SeededEvent seeded) {
        return RelayServiceFixture.owner().queryForObject("""
                SELECT encode(sha256(convert_to('p4c:v1:raw:materialization_failed:' ||
                    encode(sha256(convert_to(envelope::text, 'UTF8')), 'hex'), 'UTF8')), 'hex')
                FROM outbox_event WHERE company_id = ? AND id = ?
                """, String.class, seeded.company(), seeded.event());
    }

    private static void assertReplayRefused(UUID company, UUID event) {
        assertThatThrownBy(() -> executeReplay(company, event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay refused");
    }

    private static void assertReplaySideEffectsUnchanged(ConsumerServiceFixture.SeededEvent seeded,
                                                         String rawHash, String malformedHash, String otherHash,
                                                         String siblingHash, String dispatchState) {
        Set<String> hashes = ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).stream()
                .map(ConsumerServiceFixture.DiagnosticRow::payloadHash).collect(Collectors.toSet());
        assertThat(hashes).as("refused replay leaves the selected event diagnostics unchanged").isEmpty();
        assertThat(ConsumerServiceFixture.diagnostic(rawHash)).isPresent();
        assertThat(ConsumerServiceFixture.diagnostic(malformedHash)).isPresent();
        assertThat(ConsumerServiceFixture.diagnostic(otherHash)).isPresent();
        assertThat(ConsumerServiceFixture.diagnostic(siblingHash)).isPresent();
        assertThat(dispatch(seeded.event()).state()).isEqualTo(dispatchState);
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();
    }

    private static void executeReplay(UUID company, UUID event) {
        String sql = replaySql()
                .replace("<company-id>", company.toString())
                .replace("<event-id>", event.toString());
        DriverManagerDataSource dataSource = (DriverManagerDataSource) RelayServiceFixture.owner().getDataSource();
        String url = dataSource.getUrl();
        if (url != null && !url.contains("preferQueryMode=")) {
            url = url + (url.contains("?") ? "&" : "?") + "preferQueryMode=simple";
        }
        try (Connection connection = DriverManager.getConnection(url, dataSource.getUsername(),
                dataSource.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static String replaySql() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("scripts/replay_notification_event.sql"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Cannot locate scripts/replay_notification_event.sql");
        }
        try {
            return Files.readString(root.resolve("scripts/replay_notification_event.sql"),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read scripts/replay_notification_event.sql", e);
        }
    }

    private static RelayServiceFixture.DispatchRow dispatch(UUID event) {
        return RelayServiceFixture.worker().queryForObject("""
                SELECT state, attempts, lease_token, lease_until, published_at, error_code, next_attempt_at
                FROM outbox_dispatch WHERE event_id = ?
                """, (rows, index) -> new RelayServiceFixture.DispatchRow(
                rows.getString("state"),
                rows.getInt("attempts"),
                rows.getObject("lease_token", UUID.class),
                rows.getTimestamp("lease_until"),
                rows.getTimestamp("published_at"),
                rows.getString("error_code"),
                rows.getTimestamp("next_attempt_at")), event);
    }

    private static long outboxEventCount(ConsumerServiceFixture.SeededEvent seeded) {
        Long count = RelayServiceFixture.worker().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE company_id = ? AND id = ?",
                Long.class, seeded.company(), seeded.event());
        return count == null ? 0 : count;
    }

    private static long outboxRecipientCount(ConsumerServiceFixture.SeededEvent seeded) {
        Long count = RelayServiceFixture.worker().queryForObject(
                "SELECT count(*) FROM outbox_recipient WHERE company_id = ? AND event_id = ?",
                Long.class, seeded.company(), seeded.event());
        return count == null ? 0 : count;
    }

    private static long wireDeliveries() {
        JsonNode queue = RelayServiceFixture.management("GET", "/queues/%2F/" + RabbitTopology.QUEUE);
        JsonNode stats = queue.get("message_stats");
        JsonNode deliverGet = stats == null ? null : stats.get("deliver_get");
        return deliverGet == null ? 0 : deliverGet.asLong();
    }
}

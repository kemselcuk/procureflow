package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.procureflow.events.RequestEvent;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * P8.3b2 real PostgreSQL/RabbitMQ evidence for the bounded unexpected-consumer-failure policy. The
 * test-only store/parser beans throw a marker-bearing {@link IllegalStateException}, which is exactly
 * the kind of failure that used to escape to the listener and retain the delivery forever without
 * durable evidence. The delivery must instead be acknowledged only after the sanitized
 * {@code unhandled_consumer_failure} diagnostic for the exact fingerprint reached the configured
 * attempt cap, must never produce inbox/delivery/in-app/email-job effects in that path, and must stay
 * unacknowledged when that diagnostic cannot be written. Log capture proves the throwable and raw
 * body are not logged.
 */
@Import(ConsumerUnhandledFailureIT.UnexpectedFailureConfiguration.class)
class ConsumerUnhandledFailureIT extends ConsumerIntegrationTestBase {
    private static final String THROWABLE_MARKER = "UNEXPECTED-CONSUMER-MARKER-1f47";
    private static final Duration HOLD_WINDOW = Duration.ofSeconds(2);
    private static final AtomicBoolean MATERIALIZE_FAILURES = new AtomicBoolean();
    private static final AtomicBoolean PARSER_FAILURES = new AtomicBoolean();
    private static final AtomicBoolean FIND_FAILURES = new AtomicBoolean();
    private static final Logger PROCESSOR_LOGGER =
            (Logger) LoggerFactory.getLogger(ConsumerMessageProcessor.class);
    private static final Logger LISTENER_LOGGER =
            (Logger) LoggerFactory.getLogger(NotificationQueueListener.class);

    @BeforeEach
    void resetUnexpectedFailureHooks() {
        MATERIALIZE_FAILURES.set(false);
        PARSER_FAILURES.set(false);
        FIND_FAILURES.set(false);
    }

    @AfterEach
    void clearUnexpectedFailureHooks() {
        MATERIALIZE_FAILURES.set(false);
        PARSER_FAILURES.set(false);
        FIND_FAILURES.set(false);
    }

    @Test
    void unexpectedRuntimeFailureIsQuarantinedOnlyAtTheDurableAttemptCap() {
        var actor = ConsumerServiceFixture.createActor("unhandled-terminal");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        ListAppender<ILoggingEvent> logs = captureLogs();
        ConsumerServiceFixture.failDiagnosticWritesAtAttempt(2);
        MATERIALIZE_FAILURES.set(true);
        try {
            publish(seeded);
            await("the first durable unhandled-failure record", () -> attempts(seeded) == 1);

            // The local in-process budget may be exhausted while the durable counter is frozen below
            // the cap by the fault. That local state is never terminal evidence: the unacknowledged
            // delivery must stay retained without effects.
            await("held delivery visible as unacknowledged on the wire",
                    () -> ConsumerServiceFixture.queueDepth().unacknowledged() >= 1);
            assertThat(ConsumerServiceFixture.queueDepth().drained())
                    .as("no delivery may be acknowledged before durable terminal evidence")
                    .isFalse();
            sleep(HOLD_WINDOW);
            assertThat(ConsumerServiceFixture.queueDepth().drained())
                    .as("the held delivery must survive at least one full local attempt cycle")
                    .isFalse();
            assertThat(attempts(seeded)).as("the durable counter must not creep past the fault")
                    .isEqualTo(1);
            assertNoEffects(seeded);

            ConsumerServiceFixture.clearDiagnosticWriteFault();
            await("terminal durable unhandled-failure record",
                    () -> attempts(seeded) == properties.maxAttempts());
            await("acknowledged only after the terminal record",
                    () -> ConsumerServiceFixture.queueDepth().drained());

            var rows = ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event());
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.errorCode()).isEqualTo("unhandled_consumer_failure");
                assertThat(row.payloadHash()).isEqualTo(ConsumerFailureFingerprint.forEvent(
                        seeded.company(), seeded.event(), ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE));
                assertThat(row.channel()).isEqualTo("IN_APP");
                assertThat(row.attempts()).isEqualTo(properties.maxAttempts());
            });
            assertNoEffects(seeded);

            // The terminal observation must not start a hot loop that keeps incrementing the row.
            sleep(Duration.ofMillis(500));
            assertThat(attempts(seeded)).isEqualTo(properties.maxAttempts());
            assertNoLeak(logs);
        } finally {
            ConsumerServiceFixture.clearDiagnosticWriteFault();
            MATERIALIZE_FAILURES.set(false);
            detach(logs);
        }
    }

    @Test
    void failedDiagnosticWriteLeavesTheDeliveryUnacknowledged() {
        var actor = ConsumerServiceFixture.createActor("unhandled-no-evidence");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String fingerprint = ConsumerFailureFingerprint.forEvent(seeded.company(), seeded.event(),
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        ListAppender<ILoggingEvent> logs = captureLogs();
        MATERIALIZE_FAILURES.set(true);
        ConsumerServiceFixture.revokeInsert("notification_diagnostic");
        try {
            publish(seeded);
            await("held delivery visible as unacknowledged on the wire",
                    () -> ConsumerServiceFixture.queueDepth().unacknowledged() >= 1);
            sleep(HOLD_WINDOW);

            assertThat(ConsumerServiceFixture.queueDepth().drained())
                    .as("a delivery without normal effects or a durable diagnostic must not be acknowledged")
                    .isFalse();
            assertThat(ConsumerServiceFixture.diagnostic(fingerprint))
                    .as("the revoked INSERT really prevented durable evidence for this exact fingerprint")
                    .isEmpty();
            assertNoEffects(seeded);

            // Evidence access returns without a worker/channel/container restart: the retained
            // delivery now records its bounded attempts and acknowledges only at the terminal value.
            ConsumerServiceFixture.grantInsert("notification_diagnostic");
            await("terminal unhandled-failure record after evidence recovery",
                    () -> attempts(seeded) == properties.maxAttempts());
            await("acknowledged after evidence recovery", () -> ConsumerServiceFixture.queueDepth().drained());

            assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.errorCode()).isEqualTo("unhandled_consumer_failure");
                        assertThat(row.attempts()).isEqualTo(properties.maxAttempts());
                    });
            assertNoEffects(seeded);
            assertNoLeak(logs);
        } finally {
            ConsumerServiceFixture.grantInsert("notification_diagnostic");
            MATERIALIZE_FAILURES.set(false);
            detach(logs);
        }
    }

    @Test
    void unexpectedParserFailureIsBoundedUnderTheRawBodyFingerprint() {
        var actor = ConsumerServiceFixture.createActor("unhandled-parser");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String fingerprint = ConsumerFailureFingerprint.forRawBody(
                seeded.envelope().getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        ListAppender<ILoggingEvent> logs = captureLogs();
        PARSER_FAILURES.set(true);
        try {
            publish(seeded);
            await("raw-scoped terminal unhandled-failure record", () -> ConsumerServiceFixture
                    .diagnostic(fingerprint)
                    .map(row -> row.attempts() == properties.maxAttempts())
                    .orElse(false));
            await("acknowledged after raw terminal evidence",
                    () -> ConsumerServiceFixture.queueDepth().drained());

            var row = ConsumerServiceFixture.diagnostic(fingerprint).orElseThrow();
            assertThat(row.errorCode()).isEqualTo("unhandled_consumer_failure");
            assertThat(row.channel()).isEqualTo("IN_APP");
            assertThat(row.companyId()).as("no envelope identity exists to scope this record").isNull();
            assertThat(row.eventId()).isNull();
            assertThat(row.attempts()).isEqualTo(properties.maxAttempts());
            assertNoEffects(seeded);
            assertNoLeak(logs);
        } finally {
            PARSER_FAILURES.set(false);
            detach(logs);
        }
    }

    /**
     * M1 regression: a pre-canonical unexpected failure quarantines under the raw body fingerprint
     * with NULL company/event. The documented company/event replay must remove that exact raw row
     * (derived from the canonical publisher bytes) so the republished event materializes instead of
     * being acknowledged as an already-terminal no-op. Unrelated raw rows and a second replay must
     * stay untouched.
     */
    @Test
    void rawParserUnhandledQuarantineIsReplayedAndMaterializesExactlyOnce() {
        var actor = ConsumerServiceFixture.createActor("unhandled-replay-parser");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String canonicalBody = canonicalBody(seeded);
        String rawHash = ConsumerFailureFingerprint.forRawBody(
                canonicalBody.getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        assertThat(sqlRawUnhandledFingerprint(seeded))
                .as("PostgreSQL derives the same raw unhandled fingerprint from the publisher bytes")
                .isEqualTo(rawHash);
        markPublished(seeded);
        String unrelatedRawHash = ConsumerFailureFingerprint.forRawBody(
                "unrelated-raw-body".getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        insertRawDiagnostic(unrelatedRawHash, "unhandled_consumer_failure");

        PARSER_FAILURES.set(true);
        try {
            publish(canonicalBody, seeded.event(), seeded.type(), seeded.correlation(), 1);
            await("terminal raw unhandled diagnostic from the parser path", () -> ConsumerServiceFixture
                    .diagnostic(rawHash)
                    .map(row -> row.attempts() == properties.maxAttempts())
                    .orElse(false));
            await("raw quarantined delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
            var row = ConsumerServiceFixture.diagnostic(rawHash).orElseThrow();
            assertThat(row.errorCode()).isEqualTo("unhandled_consumer_failure");
            assertThat(row.companyId()).isNull();
            assertThat(row.eventId()).isNull();
            assertNoEffects(seeded);
        } finally {
            PARSER_FAILURES.set(false);
        }

        // The pre-fix no-op: while the terminal raw row is present, the same publisher bytes are
        // acknowledged as an observed redelivery without any materialization. The replay below must
        // clear that exact row so the next republish can materialize.
        publish(canonicalBody, seeded.event(), seeded.type(), seeded.correlation(), 1);
        await("terminal raw evidence acknowledges the redelivery",
                () -> ConsumerServiceFixture.queueDepth().drained());
        assertNoEffects(seeded);
        assertThat(ConsumerServiceFixture.diagnostic(rawHash).orElseThrow().attempts())
                .isEqualTo(properties.maxAttempts() + 1);

        executeReplay(seeded.company(), seeded.event());
        assertThat(ConsumerServiceFixture.diagnostic(rawHash))
                .as("the scoped replay must delete the exact raw unhandled fingerprint").isEmpty();
        assertThat(ConsumerServiceFixture.diagnostic(unrelatedRawHash))
                .as("an unrelated raw unhandled row must survive the scoped replay").isPresent();

        relay.runOnce();
        await("replayed materialization", () -> ConsumerServiceFixture.inbox(
                seeded.company(), seeded.event()).isPresent());
        await("replayed delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertMaterializedExactlyOnce(seeded);
        assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).isEmpty();

        Instant readAt = ConsumerServiceFixture.markInAppRead(seeded.company(), member.identity());
        Instant sentAt = ConsumerServiceFixture.markEmailSent(seeded.company(), member.identity());
        long deliveriesBefore = wireDeliveries();
        executeReplay(seeded.company(), seeded.event());
        relay.runOnce();
        await("second duplicate delivery observed on the wire", () -> wireDeliveries() > deliveriesBefore);
        await("second replayed delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertMaterializedExactlyOnce(seeded);
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).singleElement()
                .satisfies(notification -> assertThat(notification.readAt()).isEqualTo(readAt));
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).singleElement()
                .satisfies(job -> {
                    assertThat(job.state()).isEqualTo("SENT");
                    assertThat(job.sentAt()).isEqualTo(sentAt);
                });
        assertThat(ConsumerServiceFixture.diagnostic(unrelatedRawHash))
                .as("the second scoped replay must not delete unrelated raw rows").isPresent();
    }

    /**
     * The same raw scope arises when {@code findCanonical} throws unexpectedly before the canonical
     * object is assigned. The received bytes are still exactly the relay's {@code envelope::text}, so
     * the same SQL derivation clears the row and the event materializes after replay.
     */
    @Test
    void rawFindCanonicalUnhandledQuarantineIsReplayedAndMaterializesExactlyOnce() {
        var actor = ConsumerServiceFixture.createActor("unhandled-replay-canonical");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String canonicalBody = canonicalBody(seeded);
        String rawHash = ConsumerFailureFingerprint.forRawBody(
                canonicalBody.getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        assertThat(sqlRawUnhandledFingerprint(seeded)).isEqualTo(rawHash);
        markPublished(seeded);

        FIND_FAILURES.set(true);
        try {
            publish(canonicalBody, seeded.event(), seeded.type(), seeded.correlation(), 1);
            await("terminal raw unhandled diagnostic from the canonical read", () -> ConsumerServiceFixture
                    .diagnostic(rawHash)
                    .map(row -> row.attempts() == properties.maxAttempts())
                    .orElse(false));
            await("raw canonical-read delivery acknowledged",
                    () -> ConsumerServiceFixture.queueDepth().drained());
            var row = ConsumerServiceFixture.diagnostic(rawHash).orElseThrow();
            assertThat(row.errorCode()).isEqualTo("unhandled_consumer_failure");
            assertThat(row.companyId()).isNull();
            assertThat(row.eventId()).isNull();
            assertNoEffects(seeded);
        } finally {
            FIND_FAILURES.set(false);
        }

        executeReplay(seeded.company(), seeded.event());
        assertThat(ConsumerServiceFixture.diagnostic(rawHash))
                .as("the scoped replay must delete the exact raw unhandled fingerprint").isEmpty();
        relay.runOnce();
        await("replayed materialization after canonical-read recovery", () -> ConsumerServiceFixture.inbox(
                seeded.company(), seeded.event()).isPresent());
        await("replayed delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertMaterializedExactlyOnce(seeded);
        assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).isEmpty();
    }

    /** L1: the raw parser path must also stay unacknowledged while its diagnostic cannot commit. */
    @Test
    void rawParserFailureWithoutDiagnosticEvidenceStaysUnacknowledged() {
        var actor = ConsumerServiceFixture.createActor("unhandled-noevi");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        String rawHash = ConsumerFailureFingerprint.forRawBody(
                seeded.envelope().getBytes(StandardCharsets.UTF_8),
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        PARSER_FAILURES.set(true);
        ConsumerServiceFixture.revokeInsert("notification_diagnostic");
        try {
            publish(seeded);
            await("held parser-failure delivery visible as unacknowledged",
                    () -> ConsumerServiceFixture.queueDepth().unacknowledged() >= 1);
            sleep(HOLD_WINDOW);
            assertThat(ConsumerServiceFixture.queueDepth().drained())
                    .as("no raw parser failure may be acknowledged without durable evidence").isFalse();
            assertThat(ConsumerServiceFixture.diagnostic(rawHash))
                    .as("the revoked INSERT really prevented the raw evidence").isEmpty();
            assertNoEffects(seeded);

            ConsumerServiceFixture.grantInsert("notification_diagnostic");
            await("terminal raw parser-failure record", () -> ConsumerServiceFixture.diagnostic(rawHash)
                    .map(row -> row.attempts() == properties.maxAttempts())
                    .orElse(false));
            await("acknowledged after raw parser evidence", () -> ConsumerServiceFixture.queueDepth().drained());
            var row = ConsumerServiceFixture.diagnostic(rawHash).orElseThrow();
            assertThat(row.errorCode()).isEqualTo("unhandled_consumer_failure");
            assertThat(row.companyId()).isNull();
            assertThat(row.eventId()).isNull();
            assertThat(row.attempts()).isEqualTo(properties.maxAttempts());
            assertNoEffects(seeded);
        } finally {
            ConsumerServiceFixture.grantInsert("notification_diagnostic");
            PARSER_FAILURES.set(false);
        }
    }

    /** L1: a terminal unhandled row observes a later delivery without re-materializing. */
    @Test
    void terminalUnhandledRecordObservesRedeliveryWithoutMaterializing() {
        var actor = ConsumerServiceFixture.createActor("unhandled-redelivery");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        MATERIALIZE_FAILURES.set(true);
        try {
            publish(seeded);
            await("terminal unhandled record", () -> attempts(seeded) == properties.maxAttempts());
            await("initial delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
            assertNoEffects(seeded);

            MATERIALIZE_FAILURES.set(false);
            publish(seeded);
            await("redelivery observed on the terminal record",
                    () -> attempts(seeded) == properties.maxAttempts() + 1);
            await("redelivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
            assertNoEffects(seeded);
            assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.errorCode()).isEqualTo("unhandled_consumer_failure");
                        assertThat(row.attempts()).isEqualTo(properties.maxAttempts() + 1);
                    });
        } finally {
            MATERIALIZE_FAILURES.set(false);
        }
    }

    private int attempts(ConsumerServiceFixture.SeededEvent seeded) {
        return ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).stream()
                .mapToInt(ConsumerServiceFixture.DiagnosticRow::attempts).sum();
    }

    private static void assertNoEffects(ConsumerServiceFixture.SeededEvent seeded) {
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();
    }

    private static void assertMaterializedExactlyOnce(ConsumerServiceFixture.SeededEvent seeded) {
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isEqualTo(1);
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2);
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).hasSize(1);
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).hasSize(1);
    }

    /** The relay's exact publisher bytes; raw fingerprints must be derived from this representation. */
    private static String canonicalBody(ConsumerServiceFixture.SeededEvent seeded) {
        String body = RelayServiceFixture.owner().queryForObject(
                "SELECT envelope::text FROM outbox_event WHERE company_id = ? AND id = ?",
                String.class, seeded.company(), seeded.event());
        return Objects.requireNonNull(body, "the seeded canonical event is missing");
    }

    private static void markPublished(ConsumerServiceFixture.SeededEvent seeded) {
        int updated = RelayServiceFixture.owner().update("""
                UPDATE outbox_dispatch SET state = 'PUBLISHED', attempts = 1, published_at = now(),
                next_attempt_at = now(), lease_token = NULL, lease_until = NULL, error_code = NULL
                WHERE company_id = ? AND event_id = ?
                """, seeded.company(), seeded.event());
        assertThat(updated).as("component setup marks the seeded dispatch row published").isEqualTo(1);
    }

    private static void insertRawDiagnostic(String payloadHash, String errorCode) {
        RelayServiceFixture.owner().update("""
                INSERT INTO notification_diagnostic
                    (payload_hash, company_id, event_id, channel, error_code, attempts)
                VALUES (?, NULL, NULL, 'IN_APP', ?, 1)
                """, payloadHash, errorCode);
    }

    /** Cumulative broker deliveries for the queue, from the management stats counter. */
    private static long wireDeliveries() {
        var queue = RelayServiceFixture.management("GET", "/queues/%2F/" + RabbitTopology.QUEUE);
        var stats = queue.get("message_stats");
        var deliverGet = stats == null ? null : stats.get("deliver_get");
        return deliverGet == null ? 0 : deliverGet.asLong();
    }

    /** Independent SQL rendering of the raw unhandled fingerprint the replay script must derive. */
    private static String sqlRawUnhandledFingerprint(ConsumerServiceFixture.SeededEvent seeded) {
        return RelayServiceFixture.owner().queryForObject("""
                SELECT encode(sha256(convert_to('p4c:v1:raw:unhandled_consumer_failure:' ||
                    encode(sha256(convert_to(envelope::text, 'UTF8')), 'hex'), 'UTF8')), 'hex')
                FROM outbox_event WHERE company_id = ? AND id = ?
                """, String.class, seeded.company(), seeded.event());
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

    private static ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        PROCESSOR_LOGGER.addAppender(appender);
        LISTENER_LOGGER.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        PROCESSOR_LOGGER.detachAppender(appender);
        LISTENER_LOGGER.detachAppender(appender);
    }

    private static void assertNoLeak(ListAppender<ILoggingEvent> logs) {
        assertThat(logs.list).as("the failure path must emit sanitized diagnostics").isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getThrowableProxy())
                    .as("an unexpected consumer failure must never be logged with its throwable").isNull();
            assertThat(event.getFormattedMessage())
                    .doesNotContain(THROWABLE_MARKER)
                    .doesNotContain("\"schemaVersion\"")
                    .doesNotContain("\"recipients\"");
        });
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while observing the held delivery", e);
        }
    }

    /** Replaces the production store/parser with delegating test doubles that can throw on demand. */
    @TestConfiguration(proxyBeanMethods = false)
    static class UnexpectedFailureConfiguration {

        @Bean
        @Primary
        ConsumerStore unexpectedFailureConsumerStore(JdbcTemplate jdbc,
                                                     PlatformTransactionManager transactionManager) {
            return new FailingConsumerStore(jdbc, transactionManager);
        }

        @Bean
        @Primary
        ConsumerEnvelopeParser unexpectedFailureParser() {
            return new FailingEnvelopeParser();
        }
    }

    static final class FailingConsumerStore extends ConsumerStore {
        FailingConsumerStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
            super(jdbc, transactionManager);
        }

        @Override
        public Optional<CanonicalEvent> findCanonical(UUID companyId, UUID eventId) {
            if (FIND_FAILURES.get()) {
                throw new IllegalStateException(THROWABLE_MARKER);
            }
            return super.findCanonical(companyId, eventId);
        }

        @Override
        public MaterializationResult materialize(CanonicalEvent canonical,
                                                 List<RequestEvent.Recipient> recipients) {
            if (MATERIALIZE_FAILURES.get()) {
                throw new IllegalStateException(THROWABLE_MARKER);
            }
            return super.materialize(canonical, recipients);
        }
    }

    static final class FailingEnvelopeParser extends ConsumerEnvelopeParser {
        @Override
        public Result parse(byte[] body) {
            if (PARSER_FAILURES.get()) {
                throw new IllegalStateException(THROWABLE_MARKER);
            }
            return super.parse(body);
        }
    }
}

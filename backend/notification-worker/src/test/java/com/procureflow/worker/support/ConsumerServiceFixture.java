package com.procureflow.worker.support;

import com.procureflow.worker.config.RabbitTopology;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Consumer-test fixture on top of the shared real-service fixture. It seeds canonical database
 * events as setup (never as business-flow proof), reads the delivery/diagnostic tables through the
 * restricted worker login and uses the owner (container superuser) only for state that belongs to
 * another component (read state, an SMTP-sent job) or for fault injection.
 */
public final class ConsumerServiceFixture {
    private static final Map<String, String[]> TRANSITIONS = Map.of(
            "request.submitted", new String[]{"DRAFT", "AWAITING_DEPARTMENT_APPROVAL"},
            "request.department_approved", new String[]{"AWAITING_DEPARTMENT_APPROVAL", "AWAITING_FINANCE_APPROVAL"},
            "request.approved", new String[]{"AWAITING_FINANCE_APPROVAL", "APPROVED"},
            "request.rejected", new String[]{"AWAITING_DEPARTMENT_APPROVAL", "REJECTED"},
            "request.cancelled", new String[]{"AWAITING_DEPARTMENT_APPROVAL", "CANCELLED"});

    private ConsumerServiceFixture() {
    }

    public static void reset() {
        RelayServiceFixture.reset();
    }

    /** Company and one department, together with a seeded identity/membership pair. */
    public static Actor createActor(String slug) {
        JdbcTemplate owner = RelayServiceFixture.owner();
        UUID company = UUID.randomUUID();
        UUID department = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        owner.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                company, slug + "-" + company, "Consumer fixture " + slug, Timestamp.from(now));
        owner.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, 'Engineering')",
                department, company);
        owner.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', ?)",
                identity, identity.toString());
        owner.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                + "VALUES (?, ?, ?, ?, true)", membership, company, identity, department);
        return new Actor(company, department, identity, membership);
    }

    /** Adds one more active or inactive membership; an identity may only be active in one company. */
    public static Membership createMembership(UUID company, UUID department, boolean active) {
        UUID identity = UUID.randomUUID();
        RelayServiceFixture.owner().update("INSERT INTO app_identity (id, issuer, subject) "
                + "VALUES (?, 'https://fixture.invalid', ?)", identity, identity.toString());
        return new Membership(identity, createMembershipForIdentity(company, department, identity, active));
    }

    /** Creates a membership for an existing identity, e.g. the same person in another company. */
    public static UUID createMembershipForIdentity(UUID company, UUID department, UUID identity, boolean active) {
        UUID membership = UUID.randomUUID();
        RelayServiceFixture.owner().update("INSERT INTO membership "
                + "(id, company_id, identity_id, department_id, active) VALUES (?, ?, ?, ?, ?)",
                membership, company, identity, department, active);
        return membership;
    }

    public static UUID createDepartment(UUID company, String name) {
        UUID department = UUID.randomUUID();
        RelayServiceFixture.owner().update("INSERT INTO department (id, company_id, name) VALUES (?, ?, ?)",
                department, company, name);
        return department;
    }

    public static UUID createCompany(String slug) {
        UUID company = UUID.randomUUID();
        RelayServiceFixture.owner().update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, now())",
                company, slug + "-" + company, "Consumer fixture " + slug);
        return company;
    }


    public static void setMembershipActive(UUID membership, boolean active) {
        RelayServiceFixture.owner().update("UPDATE membership SET active = ? WHERE id = ?", active, membership);
    }

    /** Seeds one immutable canonical event and its recipient snapshot for the given company. */
    public static SeededEvent seedEvent(Actor actor, String type, List<RecipientSeed> recipients) {
        String[] transition = Objects.requireNonNull(TRANSITIONS.get(type), "unsupported fixture type " + type);
        JdbcTemplate owner = RelayServiceFixture.owner();
        UUID request = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID eventActor = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String envelope = envelope(event, type, actor.company(), request, correlation, occurredAt,
                transition[0], transition[1], 1, eventActor, recipients);
        owner.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id,
                department_id, title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'Consumer fixture request', 'Fixture only', 10.00, ?, ?, ?)
                """, request, actor.company(), actor.identity(), actor.membership(), actor.department(),
                Timestamp.from(occurredAt), Timestamp.from(occurredAt), correlation);
        owner.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id,
                occurred_at, envelope) VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, event, actor.company(), request, type, correlation, Timestamp.from(occurredAt), envelope);
        for (RecipientSeed recipient : recipients) {
            owner.update("INSERT INTO outbox_recipient "
                    + "(company_id, event_id, identity_id, membership_id, in_app, email) VALUES (?, ?, ?, ?, ?, ?)",
                    actor.company(), event, recipient.identityId(), recipient.membershipId(),
                    recipient.inApp(), recipient.email());
        }
        return new SeededEvent(actor.company(), actor.department(), request, eventActor, event, correlation, type,
                transition[0], transition[1], occurredAt, envelope);
    }

    /** Builds an envelope string for a seeded event; tests may mutate it for negative variants. */
    public static String envelope(UUID event, String type, UUID company, UUID request, UUID correlation,
                                  Instant occurredAt, String fromStatus, String toStatus, long requestVersion,
                                  UUID actorIdentityId, List<RecipientSeed> recipients) {
        List<String> recipientJson = new ArrayList<>(recipients.size());
        for (RecipientSeed recipient : recipients) {
            List<String> channels = new ArrayList<>(2);
            if (recipient.inApp()) {
                channels.add("\"IN_APP\"");
            }
            if (recipient.email()) {
                channels.add("\"EMAIL\"");
            }
            recipientJson.add("{\"identityId\":\"%s\",\"membershipId\":\"%s\",\"channels\":[%s]}"
                    .formatted(recipient.identityId(), recipient.membershipId(), String.join(",", channels)));
        }
        return ("{\"eventId\":\"%s\",\"schemaVersion\":1,\"type\":\"%s\",\"companyId\":\"%s\","
                + "\"requestId\":\"%s\",\"correlationId\":\"%s\",\"occurredAt\":\"%s\","
                + "\"payload\":{\"fromStatus\":\"%s\",\"toStatus\":\"%s\",\"requestVersion\":%d,"
                + "\"actorIdentityId\":\"%s\"},\"recipients\":[%s]}")
                .formatted(event, type, company, request, correlation, occurredAt, fromStatus, toStatus,
                        requestVersion, actorIdentityId, String.join(",", recipientJson));
    }

    public static Optional<Instant> inbox(UUID company, UUID event) {
        return RelayServiceFixture.worker().query(
                        "SELECT processed_at FROM notification_inbox WHERE company_id = ? AND event_id = ?",
                        (rows, index) -> rows.getTimestamp("processed_at").toInstant(), company, event)
                .stream().findFirst();
    }

    public static List<DeliveryRow> deliveries(UUID company, UUID event) {
        return RelayServiceFixture.worker().query("""
                SELECT identity_id, membership_id, channel, outcome, reason_code FROM notification_delivery
                WHERE company_id = ? AND event_id = ? ORDER BY identity_id, channel
                """, (rows, index) -> new DeliveryRow(
                        rows.getObject("identity_id", UUID.class),
                        rows.getObject("membership_id", UUID.class),
                        rows.getString("channel"),
                        rows.getString("outcome"),
                        rows.getString("reason_code")), company, event);
    }

    public static List<InAppRow> inAppNotifications(UUID company, UUID event) {
        return RelayServiceFixture.worker().query("""
                SELECT identity_id, membership_id, read_at, created_at FROM in_app_notification
                WHERE company_id = ? AND event_id = ? ORDER BY identity_id
                """, (rows, index) -> new InAppRow(
                        rows.getObject("identity_id", UUID.class),
                        rows.getObject("membership_id", UUID.class),
                        instant(rows.getTimestamp("read_at")),
                        rows.getTimestamp("created_at").toInstant()), company, event);
    }

    public static List<EmailJobRow> emailJobs(UUID company, UUID event) {
        return RelayServiceFixture.worker().query("""
                SELECT id, identity_id, recipient_address, state, attempts, next_attempt_at, sent_at, error_code
                FROM email_job WHERE company_id = ? AND event_id = ? ORDER BY identity_id
                """, (rows, index) -> new EmailJobRow(
                        rows.getObject("id", UUID.class),
                        rows.getObject("identity_id", UUID.class),
                        rows.getString("recipient_address"),
                        rows.getString("state"),
                        rows.getInt("attempts"),
                        rows.getTimestamp("next_attempt_at").toInstant(),
                        instant(rows.getTimestamp("sent_at")),
                        rows.getString("error_code")), company, event);
    }

    public static List<DiagnosticRow> diagnostics(UUID company, UUID event) {
        return RelayServiceFixture.worker().query("""
                SELECT payload_hash, company_id, event_id, channel, error_code, attempts, first_seen_at, last_seen_at
                FROM notification_diagnostic WHERE company_id = ? AND event_id = ? ORDER BY payload_hash
                """, (rows, index) -> new DiagnosticRow(
                        rows.getString("payload_hash"),
                        rows.getObject("company_id", UUID.class),
                        rows.getObject("event_id", UUID.class),
                        rows.getString("channel"),
                        rows.getString("error_code"),
                        rows.getInt("attempts"),
                        rows.getTimestamp("first_seen_at").toInstant(),
                        rows.getTimestamp("last_seen_at").toInstant()), company, event);
    }

    public static Optional<DiagnosticRow> diagnostic(String payloadHash) {
        return RelayServiceFixture.worker().query("""
                SELECT payload_hash, company_id, event_id, channel, error_code, attempts, first_seen_at, last_seen_at
                FROM notification_diagnostic WHERE payload_hash = ?
                """, (rows, index) -> new DiagnosticRow(
                        rows.getString("payload_hash"),
                        rows.getObject("company_id", UUID.class),
                        rows.getObject("event_id", UUID.class),
                        rows.getString("channel"),
                        rows.getString("error_code"),
                        rows.getInt("attempts"),
                        rows.getTimestamp("first_seen_at").toInstant(),
                        rows.getTimestamp("last_seen_at").toInstant()), payloadHash)
                .stream().findFirst();
    }

    public static List<DiagnosticRow> diagnosticsWithoutScope() {
        return RelayServiceFixture.worker().query("""
                SELECT payload_hash, company_id, event_id, channel, error_code, attempts, first_seen_at, last_seen_at
                FROM notification_diagnostic WHERE company_id IS NULL ORDER BY last_seen_at
                """, (rows, index) -> new DiagnosticRow(
                        rows.getString("payload_hash"),
                        rows.getObject("company_id", UUID.class),
                        rows.getObject("event_id", UUID.class),
                        rows.getString("channel"),
                        rows.getString("error_code"),
                        rows.getInt("attempts"),
                        rows.getTimestamp("first_seen_at").toInstant(),
                        rows.getTimestamp("last_seen_at").toInstant()));
    }

    public static long count(String table, UUID company, UUID event) {
        Long count = RelayServiceFixture.worker().queryForObject(
                "SELECT count(*) FROM " + table + " WHERE company_id = ? AND event_id = ?",
                Long.class, company, event);
        return count == null ? 0 : count;
    }

    public static long countByCompany(String table, UUID company) {
        Long count = RelayServiceFixture.worker().queryForObject(
                "SELECT count(*) FROM " + table + " WHERE company_id = ?", Long.class, company);
        return count == null ? 0 : count;
    }

    public static long total(String table) {
        Long count = RelayServiceFixture.worker().queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    public static String dispatchState(UUID event) {
        return RelayServiceFixture.worker().queryForObject(
                "SELECT state FROM outbox_dispatch WHERE event_id = ?", String.class, event);
    }

    /** Simulates a user read through the application's read-state column. */
    public static Instant markInAppRead(UUID company, UUID identity) {
        Instant readAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RelayServiceFixture.owner().update("UPDATE in_app_notification SET read_at = ? "
                + "WHERE company_id = ? AND identity_id = ?", Timestamp.from(readAt), company, identity);
        return readAt;
    }

    /** Simulates the SMTP runner's terminal state; the consumer must never reset it on redelivery. */
    public static Instant markEmailSent(UUID company, UUID identity) {
        Instant sentAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RelayServiceFixture.owner().update("UPDATE email_job SET state = 'SENT', sent_at = ? "
                + "WHERE company_id = ? AND identity_id = ? AND state = 'PENDING'", Timestamp.from(sentAt),
                company, identity);
        return sentAt;
    }

    /** Fault injection: removes one worker write privilege; {@link #grantInsert(String)} restores it. */
    public static void revokeInsert(String table) {
        RelayServiceFixture.owner().execute("REVOKE INSERT ON " + table + " FROM procureflow_worker");
    }

    public static void grantInsert(String table) {
        RelayServiceFixture.owner().execute("GRANT INSERT ON " + table + " TO procureflow_worker");
    }

    /**
     * Fault injection: makes every diagnostic write fail once its attempt counter would reach the
     * threshold, so the durable attempt counter stops below the configured cap while the in-process
     * budget keeps failing. Owner privileges only; the worker keeps its ordinary grants.
     */
    public static void failDiagnosticWritesAtAttempt(int threshold) {
        JdbcTemplate owner = RelayServiceFixture.owner();
        owner.execute("""
                CREATE OR REPLACE FUNCTION notification_diagnostic_attempt_cap() RETURNS trigger
                LANGUAGE plpgsql AS $fault$
                BEGIN
                    IF NEW.attempts >= %d THEN
                        RAISE EXCEPTION 'diagnostic attempt cap fault injection';
                    END IF;
                    RETURN NEW;
                END
                $fault$;
                """.formatted(threshold));
        owner.execute("DROP TRIGGER IF EXISTS notification_diagnostic_attempt_cap ON notification_diagnostic");
        owner.execute("""
                CREATE TRIGGER notification_diagnostic_attempt_cap
                BEFORE INSERT OR UPDATE ON notification_diagnostic
                FOR EACH ROW EXECUTE FUNCTION notification_diagnostic_attempt_cap()
                """);
    }

    public static void clearDiagnosticWriteFault() {
        RelayServiceFixture.owner().execute(
                "DROP TRIGGER IF EXISTS notification_diagnostic_attempt_cap ON notification_diagnostic");
        RelayServiceFixture.owner().execute("DROP FUNCTION IF EXISTS notification_diagnostic_attempt_cap()");
    }

    /**
     * Fault injection: disables the worker's materialization write and both directions of the
     * diagnostic/evidence channel, so no effect and no attempt evidence can commit while the fault
     * lasts. This is a PostgreSQL privilege fault, not a mock: every store call fails in the real
     * database. {@link #restoreDatabaseAccess()} ends it.
     */
    public static void suspendDatabaseAccess() {
        JdbcTemplate owner = RelayServiceFixture.owner();
        owner.execute("REVOKE INSERT ON email_job FROM procureflow_worker");
        owner.execute("REVOKE SELECT, INSERT ON notification_diagnostic FROM procureflow_worker");
    }

    public static void restoreDatabaseAccess() {
        JdbcTemplate owner = RelayServiceFixture.owner();
        owner.execute("GRANT INSERT ON email_job TO procureflow_worker");
        owner.execute("GRANT SELECT, INSERT, UPDATE (attempts, last_seen_at) ON notification_diagnostic "
                + "TO procureflow_worker");
    }

    /** Privileged operator statement from the documented replay procedure; owner-scoped only. */
    public static void deleteDiagnostics(UUID company, UUID event) {
        RelayServiceFixture.owner().update(
                "DELETE FROM notification_diagnostic WHERE company_id = ? AND event_id = ?", company, event);
    }

    public static void deleteDiagnostic(String payloadHash) {
        RelayServiceFixture.owner().update("DELETE FROM notification_diagnostic WHERE payload_hash = ?", payloadHash);
    }

    /**
     * Privileged operator replay step: re-drives one stable event through the real relay. It is
     * scoped to the exact event and refuses to touch a row that is currently live-leased, so an
     * in-flight relay claim can never be stolen; it resets only mutable dispatch state and never
     * touches the inbox/delivery/in-app/email-job effects or the immutable event rows, so a replay
     * is duplicate-suppressed rather than re-materialized. It requires the owner login, i.e. an
     * explicit operator action outside the worker's grants.
     */
    public static void resetDispatch(UUID event) {
        int updated = RelayServiceFixture.owner().update("""
                UPDATE outbox_dispatch SET state = 'PENDING', attempts = 0, next_attempt_at = now(),
                error_code = NULL, lease_token = NULL, lease_until = NULL, published_at = NULL
                WHERE event_id = ? AND (lease_until IS NULL OR lease_until < now())
                """, event);
        if (updated == 0) {
            throw new IllegalStateException("Refusing to replay event " + event
                    + ": its dispatch row is live-leased");
        }
    }

    /**
     * Queue counters. RabbitMQ's management stats entry for a freshly declared queue may be absent
     * for the first collection interval, so a missing counter is retried before it is treated as an
     * error.
     */
    public static QueueDepth queueDepth() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (true) {
            JsonNode queue = RelayServiceFixture.management("GET", "/queues/%2F/" + RabbitTopology.QUEUE);
            JsonNode ready = queue.get("messages_ready");
            JsonNode unacknowledged = queue.get("messages_unacknowledged");
            if (ready != null && unacknowledged != null) {
                return new QueueDepth(ready.asInt(), unacknowledged.asInt());
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Rabbit management response has no queue counters: " + queue);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while reading queue counters", e);
            }
        }
    }

    public static void await(Duration timeout, String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Actor(UUID company, UUID department, UUID identity, UUID membership) {
    }

    public record Membership(UUID identity, UUID membership) {
    }

    public record RecipientSeed(UUID identityId, UUID membershipId, boolean inApp, boolean email) {

        /**
         * The frozen {@code outbox_recipient} table constrains both channel flags to true, so a
         * captured recipient always carries both channels; per-channel differences can only appear
         * in a forged message and are rejected against the canonical snapshot.
         */
        public static RecipientSeed both(Membership membership) {
            return new RecipientSeed(membership.identity(), membership.membership(), true, true);
        }
    }

    public record SeededEvent(UUID company, UUID department, UUID request, UUID actorIdentity, UUID event,
                              UUID correlation, String type, String fromStatus, String toStatus, Instant occurredAt,
                              String envelope) {
    }

    public record DeliveryRow(UUID identityId, UUID membershipId, String channel, String outcome,
                              String reasonCode) {
    }

    public record InAppRow(UUID identityId, UUID membershipId, Instant readAt, Instant createdAt) {
    }

    public record EmailJobRow(UUID id, UUID identityId, String recipientAddress, String state, int attempts,
                              Instant nextAttemptAt, Instant sentAt, String errorCode) {
    }

    public record DiagnosticRow(String payloadHash, UUID companyId, UUID eventId, String channel, String errorCode,
                                int attempts, Instant firstSeenAt, Instant lastSeenAt) {
    }

    public record QueueDepth(int ready, int unacknowledged) {

        public boolean drained() {
            return ready == 0 && unacknowledged == 0;
        }
    }
}

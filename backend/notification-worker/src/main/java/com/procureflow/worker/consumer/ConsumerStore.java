package com.procureflow.worker.consumer;

import com.procureflow.events.RequestEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL access for notification materialization, all through the restricted
 * {@code procureflow_worker} login. The store reads only the canonical event/recipient snapshot and
 * the worker-readable membership columns; it never reads purchases, budgets, identities or roles and
 * never mutates email delivery state beyond inserting initial PENDING jobs.
 *
 * <p>Every failure is reported as a {@link ConsumerStoreException} so the processor can apply its
 * bounded retry/quarantine policy. No transaction here spans a broker call, SMTP call or backoff.
 */
@Component
public class ConsumerStore {
    /** Event-level diagnostics use this channel; materialization failures are not channel specific. */
    static final RequestEvent.Channel DIAGNOSTIC_CHANNEL = RequestEvent.Channel.IN_APP;

    private static final String LOCK_COMPANY_SQL = "SELECT public.lock_notification_company(?)";
    private static final String CANONICAL_EVENT_SQL =
            "SELECT envelope::text FROM outbox_event WHERE company_id = ? AND id = ?";
    private static final String CANONICAL_RECIPIENTS_SQL = """
            SELECT identity_id, membership_id, in_app, email FROM outbox_recipient
            WHERE company_id = ? AND event_id = ? ORDER BY identity_id, membership_id
            """;
    private static final String INBOX_INSERT_SQL = """
            INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)
            ON CONFLICT (company_id, event_id) DO NOTHING
            """;
    private static final String INBOX_READ_SQL =
            "SELECT processed_at FROM notification_inbox WHERE company_id = ? AND event_id = ?";
    private static final String MEMBERSHIP_SQL = """
            SELECT id, company_id, identity_id, active FROM membership WHERE id IN (:ids)
            """;
    private static final String DIAGNOSTIC_EVIDENCE_SQL =
            "SELECT error_code, attempts FROM notification_diagnostic WHERE payload_hash = ?";
    private static final String DIAGNOSTIC_UPSERT_SQL = """
            INSERT INTO notification_diagnostic
                (payload_hash, company_id, event_id, channel, error_code, attempts, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, 1, now(), now())
            ON CONFLICT (payload_hash) DO UPDATE
            SET attempts = notification_diagnostic.attempts + 1, last_seen_at = now()
            RETURNING attempts
            """;
    private static final String DELIVERY_INSERT_SQL = """
            INSERT INTO notification_delivery
                (company_id, event_id, identity_id, membership_id, channel, outcome, reason_code)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
            """;
    private static final String IN_APP_INSERT_SQL = """
            INSERT INTO in_app_notification (delivery_id, company_id, event_id, identity_id, membership_id)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String EMAIL_JOB_INSERT_SQL = """
            INSERT INTO email_job (delivery_id, company_id, event_id, identity_id, membership_id)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;

    ConsumerStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Loads the immutable canonical snapshot for exactly this company/event pair. The two reads are
     * separate autocommit statements because both tables are append-only for the application role.
     */
    public Optional<CanonicalEvent> findCanonical(UUID companyId, UUID eventId) {
        try {
            List<String> envelopes = jdbc.query(CANONICAL_EVENT_SQL,
                    (rows, index) -> rows.getString(1), companyId, eventId);
            if (envelopes.isEmpty()) {
                return Optional.empty();
            }
            List<CanonicalEvent.CanonicalRecipient> recipients = jdbc.query(CANONICAL_RECIPIENTS_SQL,
                    (rows, index) -> new CanonicalEvent.CanonicalRecipient(
                            rows.getObject("identity_id", UUID.class),
                            rows.getObject("membership_id", UUID.class),
                            rows.getBoolean("in_app"),
                            rows.getBoolean("email")),
                    companyId, eventId);
            return Optional.of(new CanonicalEvent(companyId, eventId, envelopes.get(0), recipients));
        } catch (DataAccessException e) {
            throw new ConsumerStoreException("canonical_snapshot_read_failed", e);
        }
    }

    /**
     * Durable evidence for one fingerprint (empty when no record exists): the stored error code and
     * observed attempt count. Callers must verify both against the expected code and terminal
     * threshold; a row that merely exists is not terminal proof for the transient code.
     */
    public Optional<DiagnosticEvidence> diagnosticEvidence(String payloadHash) {
        try {
            return jdbc.query(DIAGNOSTIC_EVIDENCE_SQL,
                            (rows, index) -> new DiagnosticEvidence(rows.getString("error_code"),
                                    rows.getInt("attempts")), payloadHash)
                    .stream().findFirst();
        } catch (DataAccessException e) {
            throw new ConsumerStoreException("diagnostic_read_failed", e);
        }
    }

    /**
     * Records one sanitized failure in a short transaction of its own. Only the bounded fingerprint,
     * fixed code, attempt counter and timestamps are stored; never the body, an exception or a stack
     * trace. Returns the durable attempt count for the fingerprint.
     */
    public int recordDiagnostic(String payloadHash, UUID companyId, UUID eventId, ConsumerErrorCode code) {
        try {
            Integer attempts = jdbc.queryForObject(DIAGNOSTIC_UPSERT_SQL, Integer.class, payloadHash, companyId,
                    eventId, DIAGNOSTIC_CHANNEL.name(), code.code());
            return attempts == null ? 0 : attempts;
        } catch (DataAccessException e) {
            throw new ConsumerStoreException("diagnostic_write_failed", e);
        }
    }

    public Optional<Instant> findInbox(UUID companyId, UUID eventId) {
        try {
            return jdbc.query(INBOX_READ_SQL,
                            (rows, index) -> rows.getTimestamp("processed_at").toInstant(), companyId, eventId)
                    .stream().findFirst();
        } catch (DataAccessException e) {
            throw new ConsumerStoreException("inbox_read_failed", e);
        }
    }

    /**
     * Materializes one event in a single short transaction: company SHARE barrier, inbox uniqueness
     * gate, membership revalidation and the per-recipient/channel outcome, in-app and initial email
     * rows. The inbox insert is the first write, so independent duplicate consumers serialize on its
     * unique key; a re-read of the committed marker confirms the duplicate. All effects roll back
     * together on any failure.
     */
    public MaterializationResult materialize(CanonicalEvent canonical, List<RequestEvent.Recipient> recipients) {
        MaterializationResult result;
        try {
            result = Objects.requireNonNull(transaction.execute(status -> materializeOnce(canonical, recipients)));
        } catch (DataAccessException | TransactionException e) {
            throw new ConsumerStoreException("materialization_transaction_failed", e);
        }
        if (!result.duplicate()) {
            return result;
        }
        Instant processedAt = findInbox(canonical.companyId(), canonical.eventId())
                .orElseThrow(() -> new ConsumerStoreException("inbox_marker_missing_after_conflict", null));
        return MaterializationResult.duplicate(processedAt);
    }

    private MaterializationResult materializeOnce(CanonicalEvent canonical, List<RequestEvent.Recipient> recipients) {
        // Company barrier first, matching the application's lock order; the function takes SHARE so
        // a concurrent company update/administration transaction is observed before revalidation.
        jdbc.query(LOCK_COMPANY_SQL, resultSet -> null, canonical.companyId());
        int inserted = jdbc.update(INBOX_INSERT_SQL, canonical.companyId(), canonical.eventId());
        if (inserted == 0) {
            return MaterializationResult.duplicate(null);
        }
        Map<UUID, MembershipEligibility.MembershipState> memberships = loadMemberships(recipients);
        int materialized = 0;
        int skipped = 0;
        int inApp = 0;
        int emailJobs = 0;
        for (RequestEvent.Recipient recipient : recipients) {
            String skipReason = MembershipEligibility.revalidate(canonical.companyId(), recipient,
                    memberships.get(recipient.membershipId()));
            for (RequestEvent.Channel channel : recipient.channels()) {
                if (skipReason == null) {
                    UUID deliveryId = insertDelivery(canonical, recipient, channel, "MATERIALIZED", null);
                    if (channel == RequestEvent.Channel.IN_APP) {
                        insertInApp(canonical, recipient, deliveryId);
                        inApp++;
                    } else {
                        insertEmailJob(canonical, recipient, deliveryId);
                        emailJobs++;
                    }
                    materialized++;
                } else {
                    insertDelivery(canonical, recipient, channel, "SKIPPED", skipReason);
                    skipped++;
                }
            }
        }
        return MaterializationResult.materialized(materialized, skipped, inApp, emailJobs);
    }

    private Map<UUID, MembershipEligibility.MembershipState> loadMemberships(List<RequestEvent.Recipient> recipients) {
        Set<UUID> ids = recipients.stream().map(RequestEvent.Recipient::membershipId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<MembershipEligibility.MembershipState> rows = named.query(MEMBERSHIP_SQL, Map.of("ids", ids),
                (resultSet, index) -> new MembershipEligibility.MembershipState(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("company_id", UUID.class),
                        resultSet.getObject("identity_id", UUID.class),
                        resultSet.getBoolean("active")));
        return rows.stream().collect(Collectors.toMap(MembershipEligibility.MembershipState::id,
                state -> state, (first, ignored) -> first));
    }

    private UUID insertDelivery(CanonicalEvent canonical, RequestEvent.Recipient recipient,
                                RequestEvent.Channel channel, String outcome, String reasonCode) {
        return jdbc.queryForObject(DELIVERY_INSERT_SQL, UUID.class, canonical.companyId(), canonical.eventId(),
                recipient.identityId(), recipient.membershipId(), channel.name(), outcome, reasonCode);
    }

    private void insertInApp(CanonicalEvent canonical, RequestEvent.Recipient recipient, UUID deliveryId) {
        jdbc.update(IN_APP_INSERT_SQL, deliveryId, canonical.companyId(), canonical.eventId(),
                recipient.identityId(), recipient.membershipId());
    }

    private void insertEmailJob(CanonicalEvent canonical, RequestEvent.Recipient recipient, UUID deliveryId) {
        // Initial state only: the SMTP runner owns every later email_job transition.
        jdbc.update(EMAIL_JOB_INSERT_SQL, deliveryId, canonical.companyId(), canonical.eventId(),
                recipient.identityId(), recipient.membershipId());
    }

    /** Result of one materialization attempt. */
    public record MaterializationResult(boolean duplicate, Instant processedAt, int materialized, int skipped,
                                        int inApp, int emailJobs) {

        static MaterializationResult duplicate(Instant processedAt) {
            return new MaterializationResult(true, processedAt, 0, 0, 0, 0);
        }

        static MaterializationResult materialized(int materialized, int skipped, int inApp, int emailJobs) {
            return new MaterializationResult(false, null, materialized, skipped, inApp, emailJobs);
        }
    }

    /** Persisted diagnostic fields needed for an exact terminal-evidence decision. */
    public record DiagnosticEvidence(String errorCode, int attempts) {
    }
}

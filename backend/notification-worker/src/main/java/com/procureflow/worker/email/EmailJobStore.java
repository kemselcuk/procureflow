package com.procureflow.worker.email;

import com.procureflow.worker.config.SmtpProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL-backed email-job state machine. A claim transaction is intentionally short and only
 * locks rows: it joins the existing company barrier first ({@code lock_notification_company},
 * company SHARE) and only then locks the exact delivery row with {@code SKIP LOCKED}, never the
 * other way around. A transaction acquires at most one delivery-row lock and ends on that row, so
 * no branch waits on another company barrier while holding a delivery row. SMTP I/O always happens
 * after the claim transaction has committed. Completion and failure saves lock the exact row first
 * so the live database clock is evaluated after any lock wait, and only the current unexpired
 * fencing token may write.
 *
 * <p>Candidate discovery is a bounded, rotating keyset window: when every candidate of one window
 * is held by another transaction, the next claim attempt examines the window strictly after the
 * last one instead of re-reading the same locked prefix. Any progress restarts the rotation at the
 * head and reaching the due set's tail wraps it, so a locked prefix of any length cannot starve
 * eligible rows behind it while the examined candidate count per attempt stays bounded by
 * {@code work-limit}.
 */
@Component
public class EmailJobStore {

    /**
     * Bounded cleanup of crash-loop work. The selection is a materialized, skip-locked CTE with a
     * hard LIMIT (never a locking {@code IN (SELECT ...)} that the planner could rescan) so a
     * locked exhausted row is skipped without stalling unrelated claims.
     */
    private static final String QUARANTINE_EXHAUSTED_SQL = """
            WITH exhausted AS MATERIALIZED (
                SELECT c.id FROM email_job c
                WHERE c.attempts >= ?
                  AND ((c.state = 'PENDING' AND c.next_attempt_at <= now())
                    OR (c.state = 'LEASED' AND c.lease_until <= now()))
                ORDER BY c.next_attempt_at, c.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE email_job j
            SET state = 'QUARANTINED', error_code = 'attempts_exhausted',
                lease_token = NULL, lease_until = NULL
            FROM exhausted e
            WHERE j.id = e.id
            """;

    /**
     * Non-locking candidate discovery after the rotating keyset cursor. The authoritative due
     * check happens under the row lock; this predicate only continues the due order strictly after
     * the previously examined window so a locked prefix cannot hide later due rows.
     */
    private static final String DISCOVER_AFTER_SQL = """
            SELECT c.id, c.company_id, c.next_attempt_at FROM email_job c
            WHERE ((c.state = 'PENDING' AND c.next_attempt_at <= now())
                OR (c.state = 'LEASED' AND c.lease_until <= now()))
              AND (c.next_attempt_at, c.id) > (?, ?)
            ORDER BY c.next_attempt_at, c.id
            LIMIT ?
            """;

    /** Head of the same bounded due window: the first attempt and every wrap-around. */
    private static final String DISCOVER_HEAD_SQL = """
            SELECT c.id, c.company_id, c.next_attempt_at FROM email_job c
            WHERE (c.state = 'PENDING' AND c.next_attempt_at <= now())
               OR (c.state = 'LEASED' AND c.lease_until <= now())
            ORDER BY c.next_attempt_at, c.id
            LIMIT ?
            """;

    /** Company barrier first; the security-definer function raises for an unknown company. */
    private static final String LOCK_COMPANY_SQL = "SELECT public.lock_notification_company(?)";

    /**
     * Exact delivery-row lock after the company barrier. {@code SKIP LOCKED} means a concurrently
     * claimed row is skipped instead of waiting, and the due flag is recomputed on the live
     * database clock while the lock is held.
     */
    private static final String LOCK_JOB_SQL = """
            SELECT id, company_id, event_id, identity_id, membership_id, recipient_address, attempts,
                   ((state = 'PENDING' AND next_attempt_at <= clock_timestamp())
                     OR (state = 'LEASED' AND lease_until <= clock_timestamp())) AS due
            FROM email_job
            WHERE id = ? AND company_id = ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String QUARANTINE_ROW_SQL = """
            UPDATE email_job
            SET state = 'QUARANTINED', error_code = 'attempts_exhausted',
                lease_token = NULL, lease_until = NULL
            WHERE id = ? AND company_id = ?
            """;

    private static final String ELIGIBILITY_SQL = """
            SELECT active, identity_id = ? AS identity_matches
            FROM membership WHERE id = ? AND company_id = ?
            """;

    private static final String SKIP_SQL = """
            UPDATE email_job
            SET state = 'SKIPPED', error_code = ?, lease_token = NULL, lease_until = NULL
            WHERE id = ? AND company_id = ?
            """;

    private static final String LEASE_SQL = """
            UPDATE email_job
            SET state = 'LEASED', attempts = attempts + 1, lease_token = ?, error_code = NULL,
                lease_until = clock_timestamp() + make_interval(secs => ?)
            WHERE id = ? AND company_id = ?
            RETURNING attempts
            """;

    private static final String EVENT_METADATA_SQL = """
            SELECT event_type, request_id, correlation_id FROM outbox_event
            WHERE company_id = ? AND id = ?
            """;

    /**
     * Serializes a completion against concurrent claim and completion transactions. A blocked
     * UPDATE evaluates its WHERE clause before it waits, so completion must lock the exact row
     * first and run the fenced statement only while that lock is held.
     */
    private static final String LOCK_LEASE_SQL = """
            SELECT lease_token FROM email_job WHERE id = ? AND company_id = ? FOR UPDATE
            """;

    private static final String MARK_SENT_SQL = """
            UPDATE email_job
            SET state = 'SENT', sent_at = clock_timestamp(), error_code = NULL,
                lease_token = NULL, lease_until = NULL
            WHERE id = ? AND company_id = ? AND state = 'LEASED'
              AND lease_token = ? AND lease_until > clock_timestamp()
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE email_job
            SET state = CASE WHEN ? OR attempts >= ? THEN 'QUARANTINED' ELSE 'PENDING' END,
                next_attempt_at = clock_timestamp() + make_interval(secs => ?),
                error_code = ?,
                lease_token = NULL,
                lease_until = NULL
            WHERE id = ? AND company_id = ? AND state = 'LEASED'
              AND lease_token = ? AND lease_until > clock_timestamp()
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    /** Rotating discovery window shared across the runner's sequential claim attempts. */
    private final AtomicReference<CandidateKey> rotation = new AtomicReference<>();

    EmailJobStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Quarantines at most the configured batch of due rows whose attempts were exhausted by crashes.
     * Runs in its own short transaction without a company lock, so it can never hold a delivery row
     * while a claim transaction waits for the company barrier.
     */
    public int quarantineExhausted(int batchSize, int maxAttempts) {
        return Objects.requireNonNull(transaction.execute(status -> jdbc.update(
                QUARANTINE_EXHAUSTED_SQL, maxAttempts, batchSize)));
    }

    /** Claims at most one due job in its own short transaction; the caller sends only after commit. */
    public ClaimResult claimOne(SmtpProperties properties) {
        return Objects.requireNonNull(transaction.execute(status -> jdbc.execute(
                (ConnectionCallback<ClaimResult>) connection -> claimFrom(connection, properties, rotation))));
    }

    /**
     * Restarts the discovery rotation from the due set's head. Used by tests for deterministic
     * windows; safe at any moment because the head window is always valid and re-examining it can
     * only repeat bounded work for one claim attempt.
     */
    public void restartRotation() {
        rotation.set(null);
    }

    /**
     * Claim statements executed on a caller-provided connection without rotation state. Package
     * private so independent connection concurrency tests can hold the transaction open and
     * observe {@code SKIP LOCKED} against a deterministic head window.
     */
    static ClaimResult claimOn(Connection connection, SmtpProperties properties) throws SQLException {
        return claimFrom(connection, properties, new AtomicReference<>());
    }

    /**
     * One bounded claim attempt over a rotating candidate window. Candidates are examined with the
     * company barrier first and a non-waiting exact-row lock second: a candidate held by another
     * transaction acquires nothing and the loop may continue, but as soon as one delivery row is
     * locked the attempt ends on that row (lease, terminal skip, quarantine or not-due). That is
     * what keeps the lock order unconditional at every branch: this transaction never waits on
     * another company barrier while holding a delivery row. The rotation advances only when a
     * whole full window was unclaimable; any progress restarts it at the head, and a short window
     * means the due set's tail was reached.
     */
    private static ClaimResult claimFrom(Connection connection, SmtpProperties properties,
                                         AtomicReference<CandidateKey> rotation) throws SQLException {
        CandidateKey after = rotation.get();
        List<Candidate> candidates = discover(connection, properties.workLimit(), after);
        if (candidates.isEmpty() && after != null) {
            // The rotating window ran past the due set's tail; restart from the head in the same
            // attempt so a cursor left behind by a drained window cannot stall a poll.
            rotation.set(null);
            candidates = discover(connection, properties.workLimit(), null);
        }
        for (Candidate candidate : candidates) {
            lockCompany(connection, candidate.companyId());
            LockedJob locked = lockCandidate(connection, candidate.jobId(), candidate.companyId());
            if (locked == null) {
                // Another transaction holds the exact row; SKIP LOCKED acquired nothing here, so
                // the next candidate's company barrier may be taken without a held delivery row.
                continue;
            }
            // One delivery row is locked from here on: every branch returns without acquiring
            // anything else, and progress restarts the rotation at the head.
            rotation.set(null);
            if (!locked.due()) {
                return new ClaimResult(null, null, 0);
            }
            if (locked.attempts() >= properties.maxAttempts()) {
                quarantine(connection, locked.jobId(), locked.companyId());
                return new ClaimResult(null, null, 1);
            }
            Eligibility eligibility = eligibility(connection, locked.companyId(), locked.membershipId(),
                    locked.identityId());
            if (eligibility == Eligibility.INACTIVE) {
                skip(connection, locked, "recipient_inactive");
                return new ClaimResult(null, new SkippedEmailJob(locked.jobId(), "recipient_inactive"), 0);
            }
            if (eligibility == Eligibility.MISMATCH) {
                skip(connection, locked, "recipient_membership_mismatch");
                return new ClaimResult(null, new SkippedEmailJob(locked.jobId(), "recipient_membership_mismatch"),
                        0);
            }
            return new ClaimResult(lease(connection, locked, properties), null, 0);
        }
        if (!candidates.isEmpty()) {
            // A full window may have more due rows behind it, so the next attempt continues
            // strictly after it; a short window reached the tail and restarts at the head.
            if (candidates.size() >= properties.workLimit()) {
                Candidate last = candidates.get(candidates.size() - 1);
                rotation.set(new CandidateKey(last.nextAttemptAt(), last.jobId()));
            } else {
                rotation.set(null);
            }
        }
        return new ClaimResult(null, null, 0);
    }

    /** Moves the locked due row into a fenced LEASED state and reads the canonical metadata. */
    private static ClaimedEmailJob lease(Connection connection, LockedJob locked, SmtpProperties properties)
            throws SQLException {
        UUID leaseToken = UUID.randomUUID();
        int attempts;
        try (PreparedStatement lease = connection.prepareStatement(LEASE_SQL)) {
            lease.setObject(1, leaseToken);
            lease.setDouble(2, properties.leaseTimeout().toMillis() / 1000.0);
            lease.setObject(3, locked.jobId());
            lease.setObject(4, locked.companyId());
            try (ResultSet rows = lease.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Claimed email job disappeared under its row lock");
                }
                attempts = rows.getInt("attempts");
            }
        }
        Metadata metadata = loadMetadata(connection, locked);
        return new ClaimedEmailJob(locked.jobId(), locked.companyId(), locked.eventId(), locked.identityId(),
                locked.membershipId(), locked.recipientAddress(), attempts, leaseToken, metadata.eventType(),
                metadata.requestId(), metadata.correlationId());
    }

    /**
     * Bounded due-window discovery: from the head without a cursor, otherwise strictly after the
     * (next_attempt_at, id) key of the last unclaimable window row.
     */
    private static List<Candidate> discover(Connection connection, int limit, CandidateKey after)
            throws SQLException {
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                after == null ? DISCOVER_HEAD_SQL : DISCOVER_AFTER_SQL)) {
            int index = 1;
            if (after != null) {
                statement.setObject(index++, after.nextAttemptAt());
                statement.setObject(index++, after.jobId());
            }
            statement.setInt(index, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    candidates.add(new Candidate(rows.getObject("id", UUID.class),
                            rows.getObject("company_id", UUID.class),
                            rows.getObject("next_attempt_at", OffsetDateTime.class)));
                }
            }
        }
        return candidates;
    }

    /** Company SHARE barrier before any delivery row lock, in the shared lock order. */
    private static void lockCompany(Connection connection, UUID companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_COMPANY_SQL)) {
            statement.setObject(1, companyId);
            statement.execute();
        }
    }

    private static LockedJob lockCandidate(Connection connection, UUID jobId, UUID companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_JOB_SQL)) {
            statement.setObject(1, jobId);
            statement.setObject(2, companyId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new LockedJob(rows.getObject("id", UUID.class), rows.getObject("company_id", UUID.class),
                        rows.getObject("event_id", UUID.class), rows.getObject("identity_id", UUID.class),
                        rows.getObject("membership_id", UUID.class), rows.getString("recipient_address"),
                        rows.getInt("attempts"), rows.getBoolean("due"));
            }
        }
    }

    private static void quarantine(Connection connection, UUID jobId, UUID companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(QUARANTINE_ROW_SQL)) {
            statement.setObject(1, jobId);
            statement.setObject(2, companyId);
            statement.executeUpdate();
        }
    }

    private static void skip(Connection connection, LockedJob locked, String reasonCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SKIP_SQL)) {
            statement.setString(1, reasonCode);
            statement.setObject(2, locked.jobId());
            statement.setObject(3, locked.companyId());
            statement.executeUpdate();
        }
    }

    /**
     * Current membership state under the company barrier. {@code MISMATCH} covers a missing row or
     * a row that no longer carries the captured identity/company binding.
     */
    static Eligibility eligibility(Connection connection, UUID companyId, UUID membershipId, UUID identityId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ELIGIBILITY_SQL)) {
            statement.setObject(1, identityId);
            statement.setObject(2, membershipId);
            statement.setObject(3, companyId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Eligibility.MISMATCH;
                }
                if (!rows.getBoolean("identity_matches")) {
                    return Eligibility.MISMATCH;
                }
                return rows.getBoolean("active") ? Eligibility.ELIGIBLE : Eligibility.INACTIVE;
            }
        }
    }

    private static Metadata loadMetadata(Connection connection, LockedJob locked) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EVENT_METADATA_SQL)) {
            statement.setObject(1, locked.companyId());
            statement.setObject(2, locked.eventId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Canonical outbox event missing for email job " + locked.jobId());
                }
                return new Metadata(rows.getString("event_type"), rows.getObject("request_id", UUID.class),
                        rows.getObject("correlation_id", UUID.class));
            }
        }
    }

    /** Fenced completion: only the current owner of an unexpired lease may mark the job sent. */
    public boolean markSent(ClaimedEmailJob job) {
        return Boolean.TRUE.equals(transaction.execute(status -> stillOwnsLeasedRow(job)
                && jdbc.update(MARK_SENT_SQL, job.jobId(), job.companyId(), job.leaseToken()) == 1));
    }

    /**
     * Fenced failure: schedules a backed-off retry, or quarantines terminal/permanent failures and
     * exhausted attempts. The attempt cap and terminal flag are combined while the row lock is
     * held, and the lease expiry is judged by the live database clock after any lock wait.
     */
    public boolean markFailed(ClaimedEmailJob job, EmailFailure failure, long delayMillis, int maxAttempts) {
        return Boolean.TRUE.equals(transaction.execute(status -> stillOwnsLeasedRow(job)
                && jdbc.update(MARK_FAILED_SQL, failure.terminal(), maxAttempts, delayMillis / 1000.0,
                        failure.code(), job.jobId(), job.companyId(), job.leaseToken()) == 1));
    }

    /**
     * Locks the exact job row, waiting out any concurrent writer, and reports whether it still
     * carries the caller's fencing token. The fenced statement runs only while this lock is held.
     */
    private boolean stillOwnsLeasedRow(ClaimedEmailJob job) {
        List<UUID> tokens = jdbc.query(LOCK_LEASE_SQL,
                (rows, index) -> rows.getObject("lease_token", UUID.class),
                job.jobId(), job.companyId());
        return !tokens.isEmpty() && job.leaseToken().equals(tokens.get(0));
    }

    /** Whether the captured membership still matches the company/identity binding and is active. */
    enum Eligibility { ELIGIBLE, INACTIVE, MISMATCH }

    /** Outcome of one claim attempt: a leased job, a terminal ineligibility skip, or nothing. */
    public record ClaimResult(ClaimedEmailJob claimed, SkippedEmailJob skipped, int quarantined) {}

    /** A job whose captured recipient is no longer eligible; SKIPPED is terminal. */
    public record SkippedEmailJob(UUID jobId, String reasonCode) {}

    /** Rotation cursor: the (next_attempt_at, id) key of the last examined window row. */
    private record CandidateKey(OffsetDateTime nextAttemptAt, UUID jobId) {}

    private record LockedJob(UUID jobId, UUID companyId, UUID eventId, UUID identityId, UUID membershipId,
                             String recipientAddress, int attempts, boolean due) {}

    private record Candidate(UUID jobId, UUID companyId, OffsetDateTime nextAttemptAt) {}

    private record Metadata(String eventType, UUID requestId, UUID correlationId) {}
}

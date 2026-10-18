package com.procureflow.worker.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL-backed dispatch state machine. Claim transactions are intentionally short: they only
 * lock dispatch rows, increment bounded attempts and read the immutable envelope. Broker I/O always
 * happens after the claim transaction has committed.
 */
@Component
public class DispatchStore {
    private static final String SWEEP_EXHAUSTED_SQL = """
            WITH exhausted AS MATERIALIZED (
                SELECT c.event_id FROM outbox_dispatch c
                WHERE c.attempts >= ?
                  AND ((c.state = 'PENDING' AND c.next_attempt_at <= now())
                    OR (c.state = 'LEASED' AND c.lease_until <= now()))
                ORDER BY c.next_attempt_at, c.event_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_dispatch d
            SET state = 'QUARANTINED', error_code = 'attempts_exhausted',
                lease_token = NULL, lease_until = NULL
            FROM exhausted e
            WHERE d.event_id = e.event_id
            """;

    /**
     * Claims the next due events under the database clock. The pick is a materialized CTE so the
     * limited row-locking selection is evaluated exactly once per claim: a locking subquery inside
     * {@code IN (...)} may otherwise be rescanned by the chosen join strategy and lease more rows
     * than the configured bound.
     */
    private static final String CLAIM_SQL = """
            WITH picked AS MATERIALIZED (
                SELECT c.event_id FROM outbox_dispatch c
                WHERE ((c.state = 'PENDING' AND c.next_attempt_at <= now())
                    OR (c.state = 'LEASED' AND c.lease_until <= now()))
                  AND c.attempts < ?
                ORDER BY c.next_attempt_at, c.event_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_dispatch d
            SET state = 'LEASED',
                attempts = d.attempts + 1,
                lease_token = gen_random_uuid(),
                lease_until = now() + make_interval(secs => ?)
            FROM picked p
            WHERE d.event_id = p.event_id
            RETURNING d.event_id, d.company_id, d.attempts, d.lease_token
            """;

    /**
     * Serializes a completion against concurrent claim and completion transactions. A blocked
     * UPDATE evaluates its WHERE clause before it waits, so completion must lock the exact row
     * first and run the fenced statement only while that lock is held; the fenced statement then
     * evaluates the live database clock after the wait instead of the time it was queued.
     */
    private static final String LOCK_LEASE_SQL = """
            SELECT lease_token FROM outbox_dispatch
            WHERE event_id = ? AND company_id = ?
            FOR UPDATE
            """;

    private static final String ENVELOPE_SQL = """
            SELECT id, event_type, correlation_id, envelope::text AS envelope
            FROM outbox_event WHERE id = ANY(?)
            """;

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE outbox_dispatch
            SET state = 'PUBLISHED', published_at = clock_timestamp(), error_code = NULL,
                lease_token = NULL, lease_until = NULL
            WHERE event_id = ? AND company_id = ? AND state = 'LEASED'
              AND lease_token = ? AND lease_until > clock_timestamp()
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE outbox_dispatch
            SET state = CASE WHEN attempts >= ? THEN 'QUARANTINED' ELSE 'PENDING' END,
                next_attempt_at = clock_timestamp() + make_interval(secs => ?),
                error_code = ?,
                lease_token = NULL,
                lease_until = NULL
            WHERE event_id = ? AND company_id = ? AND state = 'LEASED'
              AND lease_token = ? AND lease_until > clock_timestamp()
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    DispatchStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Claims up to the bounded claim limit, after quarantining a bounded slice of due work that
     * exhausted its attempts. Both statements use skip-locked row selection so unrelated due work
     * keeps progressing no matter which rows another transaction holds.
     */
    public ClaimResult claimDue(RelayProperties properties, int requestedLimit) {
        int claimLimit = Math.max(1, Math.min(requestedLimit, properties.batchSize()));
        return Objects.requireNonNull(transaction.execute(status -> jdbc.execute(
                (ConnectionCallback<ClaimResult>) connection ->
                        claimDue(connection, properties, claimLimit, properties.batchSize()))));
    }

    /**
     * Claim statements executed on a caller-provided connection. Package-private for the independent
     * connection concurrency test, which holds the transaction open to observe {@code SKIP LOCKED}.
     */
    static ClaimResult claimDue(Connection connection, RelayProperties properties, int limit) throws SQLException {
        return claimDue(connection, properties, limit, limit);
    }

    static ClaimResult claimDue(Connection connection, RelayProperties properties, int claimRequest,
                                int quarantineRequest) throws SQLException {
        int claimLimit = Math.max(1, Math.min(claimRequest, properties.batchSize()));
        int quarantineLimit = Math.max(1, Math.min(quarantineRequest, properties.batchSize()));
        int quarantined;
        try (PreparedStatement sweep = connection.prepareStatement(SWEEP_EXHAUSTED_SQL)) {
            sweep.setInt(1, properties.maxAttempts());
            sweep.setInt(2, quarantineLimit);
            quarantined = sweep.executeUpdate();
        }
        List<Seed> seeds = new ArrayList<>();
        try (PreparedStatement claim = connection.prepareStatement(CLAIM_SQL)) {
            claim.setInt(1, properties.maxAttempts());
            claim.setInt(2, claimLimit);
            claim.setDouble(3, properties.leaseTimeout().toMillis() / 1000.0);
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    seeds.add(new Seed(rows.getObject("event_id", UUID.class),
                            rows.getObject("company_id", UUID.class), rows.getInt("attempts"),
                            rows.getObject("lease_token", UUID.class)));
                }
            }
        }
        if (seeds.isEmpty()) {
            return new ClaimResult(List.of(), quarantined);
        }
        Map<UUID, Envelope> envelopes = loadEnvelopes(connection, seeds);
        List<ClaimedEvent> events = new ArrayList<>(seeds.size());
        for (Seed seed : seeds) {
            Envelope envelope = envelopes.get(seed.eventId());
            if (envelope == null) {
                throw new IllegalStateException("Outbox envelope missing for claimed event " + seed.eventId());
            }
            events.add(new ClaimedEvent(seed.eventId(), seed.companyId(), seed.attempt(), seed.leaseToken(),
                    envelope.correlationId(), envelope.eventType(), envelope.envelope()));
        }
        return new ClaimResult(events, quarantined);
    }

    private static Map<UUID, Envelope> loadEnvelopes(Connection connection, List<Seed> seeds) throws SQLException {
        UUID[] ids = seeds.stream().map(Seed::eventId).toArray(UUID[]::new);
        try (PreparedStatement statement = connection.prepareStatement(ENVELOPE_SQL)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids));
            try (ResultSet rows = statement.executeQuery()) {
                List<EnvelopeRow> loaded = new ArrayList<>();
                while (rows.next()) {
                    loaded.add(new EnvelopeRow(rows.getObject("id", UUID.class), new Envelope(
                            rows.getString("event_type"), rows.getObject("correlation_id", UUID.class),
                            rows.getString("envelope"))));
                }
                return loaded.stream().collect(Collectors.toMap(EnvelopeRow::id, EnvelopeRow::envelope,
                        (first, ignored) -> first));
            }
        }
    }

    /**
     * Fenced completion: only the current owner of an unexpired lease may mark the event published.
     * The row is locked first and the live database clock decides while the lock is held, so a stale
     * owner (expired or reclaimed after the statement was queued) receives {@code false}.
     */
    public boolean markPublished(ClaimedEvent event) {
        return Boolean.TRUE.equals(transaction.execute(status -> stillOwnsLeasedRow(event)
                && jdbc.update(MARK_PUBLISHED_SQL, event.eventId(), event.companyId(), event.leaseToken()) == 1));
    }

    /**
     * Fenced failure: either schedules a backed-off retry or quarantines terminal work. The attempt
     * cap is re-checked against the locked row, never trusted from the caller alone, and the lease
     * expiry is judged by the live database clock while the row lock is held.
     */
    public boolean markFailed(ClaimedEvent event, PublishFailure failure, long delayMillis, int maxAttempts) {
        return Boolean.TRUE.equals(transaction.execute(status -> stillOwnsLeasedRow(event)
                && jdbc.update(MARK_FAILED_SQL, maxAttempts, delayMillis / 1000.0,
                        failure.code(), event.eventId(), event.companyId(), event.leaseToken()) == 1));
    }

    /**
     * Locks the exact dispatch row, waiting out any concurrent writer, and reports whether it still
     * carries the caller's fencing token. The fenced statement runs only while this lock is held, so
     * its live-clock predicate is evaluated after the wait rather than at queue time.
     */
    private boolean stillOwnsLeasedRow(ClaimedEvent event) {
        List<UUID> tokens = jdbc.query(LOCK_LEASE_SQL,
                (rows, index) -> rows.getObject("lease_token", UUID.class),
                event.eventId(), event.companyId());
        return !tokens.isEmpty() && event.leaseToken().equals(tokens.get(0));
    }

    /** Claims and quarantine work observed in one claim transaction. */
    public record ClaimResult(List<ClaimedEvent> events, int quarantined) {}

    private record Seed(UUID eventId, UUID companyId, int attempt, UUID leaseToken) {}

    private record Envelope(String eventType, UUID correlationId, String envelope) {}

    private record EnvelopeRow(UUID id, Envelope envelope) {}
}

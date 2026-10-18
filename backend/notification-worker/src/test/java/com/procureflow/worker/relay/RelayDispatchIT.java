package com.procureflow.worker.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.support.RelayServiceFixture;
import com.procureflow.worker.support.WorkerIntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Fencing, lease and attempt semantics against real PostgreSQL 18.6 through the worker login.
 * Concurrency is proven with two independent connections holding observed row locks, never sleeps.
 */
class RelayDispatchIT extends WorkerIntegrationTestBase {

    @Test
    void triggerInitializesDispatchAndClaimIncrementsAttemptsWithDatabaseClockLease() {
        var seeded = RelayServiceFixture.seedEvent();
        var initial = RelayServiceFixture.dispatch(seeded.event());
        assertThat(initial.state()).isEqualTo("PENDING");
        assertThat(initial.attempts()).isZero();
        assertThat(initial.leaseToken()).isNull();
        assertThat(initial.publishedAt()).isNull();

        var result = dispatchStore.claimDue(properties, properties.batchSize());
        assertThat(result.quarantined()).isZero();
        assertThat(result.events()).extracting(ClaimedEvent::eventId).containsExactly(seeded.event());
        var claim = result.events().get(0);
        assertThat(claim.companyId()).isEqualTo(seeded.company());
        assertThat(claim.eventType()).isEqualTo("request.submitted");
        assertThat(claim.correlationId()).isEqualTo(seeded.correlation());
        assertThat(claim.envelopeJson()).isEqualTo(seeded.envelope());
        assertThat(claim.attempt()).isEqualTo(1);

        var leased = RelayServiceFixture.dispatch(seeded.event());
        assertThat(leased.state()).isEqualTo("LEASED");
        assertThat(leased.attempts()).isEqualTo(1);
        assertThat(leased.leaseToken()).isEqualTo(claim.leaseToken());
        assertThat(leased.leaseUntil()).isAfter(Timestamp.from(Instant.now()));
    }

    @Test
    void twoClaimersOnIndependentConnectionsTakeDisjointRows() throws Exception {
        List<UUID> seeded = IntStream.range(0, 6)
                .mapToObj(index -> RelayServiceFixture.seedEvent().event())
                .toList();
        List<ClaimedEvent> firstClaims;
        List<ClaimedEvent> secondClaims;
        try (Connection first = RelayServiceFixture.workerConnection();
             Connection second = RelayServiceFixture.workerConnection()) {
            assertThat(backendPid(first)).as("independent connections must use distinct backends")
                    .isNotEqualTo(backendPid(second));
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            // The first transaction holds FOR UPDATE SKIP LOCKED row locks until it commits.
            firstClaims = DispatchStore.claimDue(first, properties, 4).events();
            assertThat(firstClaims).hasSize(4);
            secondClaims = DispatchStore.claimDue(second, properties, 4).events();
            assertThat(secondClaims).hasSize(2);
            first.commit();
            second.commit();
        }
        List<UUID> firstIds = firstClaims.stream().map(ClaimedEvent::eventId).toList();
        List<UUID> secondIds = secondClaims.stream().map(ClaimedEvent::eventId).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        List<UUID> combined = new ArrayList<>(firstIds);
        combined.addAll(secondIds);
        assertThat(combined).containsExactlyInAnyOrderElementsOf(seeded);

        List<UUID> tokens = new ArrayList<>();
        for (UUID event : seeded) {
            var row = RelayServiceFixture.dispatch(event);
            assertThat(row.state()).isEqualTo("LEASED");
            assertThat(row.attempts()).isEqualTo(1);
            tokens.add(row.leaseToken());
        }
        assertThat(tokens).doesNotHaveDuplicates();
    }

    @Test
    void claimSkipsFutureBackoffAndUnexpiredLeasesThenReclaimsExpiredOnes() {
        var backoff = RelayServiceFixture.seedEvent();
        var leased = RelayServiceFixture.seedEvent();
        RelayServiceFixture.worker().update(
                "UPDATE outbox_dispatch SET attempts = 1, next_attempt_at = now() + interval '1 hour' "
                        + "WHERE event_id = ?", backoff.event());
        RelayServiceFixture.worker().update(
                "UPDATE outbox_dispatch SET state = 'LEASED', attempts = 1, lease_token = ?, "
                        + "lease_until = now() + interval '1 hour' WHERE event_id = ?",
                UUID.randomUUID(), leased.event());

        assertThat(dispatchStore.claimDue(properties, properties.batchSize()).events())
                .as("neither backed-off nor unexpired-leased work may be claimed").isEmpty();

        RelayServiceFixture.expireLease(leased.event());
        RelayServiceFixture.worker().update(
                "UPDATE outbox_dispatch SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
                backoff.event());
        var reclaimed = dispatchStore.claimDue(properties, properties.batchSize());
        assertThat(reclaimed.events()).extracting(ClaimedEvent::eventId)
                .containsExactlyInAnyOrder(backoff.event(), leased.event());
        assertThat(reclaimed.events()).allSatisfy(claim -> assertThat(claim.attempt()).isEqualTo(2));
        assertThat(RelayServiceFixture.dispatch(backoff.event()).state()).isEqualTo("LEASED");
        assertThat(RelayServiceFixture.dispatch(leased.event()).state()).isEqualTo("LEASED");
    }

    @Test
    void wrongStaleAndExpiredOwnersCannotCompleteBeforeOrAfterReclaim() {
        var seeded = RelayServiceFixture.seedEvent();
        var first = onlyClaim();

        var impostor = new ClaimedEvent(first.eventId(), first.companyId(), first.attempt(), UUID.randomUUID(),
                first.correlationId(), first.eventType(), first.envelopeJson());
        assertThat(dispatchStore.markPublished(impostor)).as("a foreign token must never complete work").isFalse();
        assertThat(dispatchStore.markFailed(impostor, PublishFailure.BROKER_NACK, 1_000, properties.maxAttempts()))
                .isFalse();
        assertThat(RelayServiceFixture.dispatch(seeded.event()).state()).isEqualTo("LEASED");

        RelayServiceFixture.expireLease(seeded.event());
        assertThat(dispatchStore.markPublished(first)).as("an expired lease must not complete, even before reclaim")
                .isFalse();
        assertThat(dispatchStore.markFailed(first, PublishFailure.BROKER_NACK, 1_000, properties.maxAttempts()))
                .isFalse();

        var second = onlyClaim();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(dispatchStore.markPublished(first)).as("a reclaimed owner must not complete stale work").isFalse();
        assertThat(dispatchStore.markFailed(first, PublishFailure.BROKER_NACK, 1_000, properties.maxAttempts()))
                .isFalse();

        var stillLeased = RelayServiceFixture.dispatch(seeded.event());
        assertThat(stillLeased.state()).isEqualTo("LEASED");
        assertThat(stillLeased.leaseToken()).isEqualTo(second.leaseToken());
        assertThat(dispatchStore.markPublished(second)).isTrue();
        var published = RelayServiceFixture.dispatch(seeded.event());
        assertThat(published.state()).isEqualTo("PUBLISHED");
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.errorCode()).isNull();
        assertThat(published.leaseToken()).isNull();
        assertThat(published.leaseUntil()).isNull();
        assertThat(dispatchStore.markPublished(second)).as("a completed row is no longer completable").isFalse();
    }

    @Test
    void reclaimedOwnerCanScheduleBackoffButStaleOwnerCannot() {
        var seeded = RelayServiceFixture.seedEvent();
        var first = onlyClaim();
        RelayServiceFixture.expireLease(seeded.event());
        var second = onlyClaim();

        assertThat(dispatchStore.markFailed(first, PublishFailure.BROKER_NACK, 1_000, properties.maxAttempts()))
                .isFalse();
        assertThat(dispatchStore.markFailed(second, PublishFailure.BROKER_NACK, properties.retryDelayMillis(2),
                properties.maxAttempts())).isTrue();
        var row = RelayServiceFixture.dispatch(seeded.event());
        assertThat(row.state()).isEqualTo("PENDING");
        assertThat(row.errorCode()).isEqualTo("broker_nack");
        assertThat(row.leaseToken()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.nextAttemptAt()).isAfter(Timestamp.from(Instant.now()));
    }

    @Test
    void exhaustedCrashLoopIsQuarantinedWithoutPublishingOrIncrementingAttempts() {
        var crashLoop = RelayServiceFixture.seedEvent();
        RelayServiceFixture.worker().update(
                "UPDATE outbox_dispatch SET state = 'LEASED', attempts = ?, lease_token = ?, "
                        + "lease_until = now() - interval '1 second' WHERE event_id = ?",
                properties.maxAttempts(), UUID.randomUUID(), crashLoop.event());

        var result = dispatchStore.claimDue(properties, properties.batchSize());
        assertThat(result.quarantined()).isEqualTo(1);
        assertThat(result.events()).as("terminal work must not be published again").isEmpty();
        var row = RelayServiceFixture.dispatch(crashLoop.event());
        assertThat(row.state()).isEqualTo("QUARANTINED");
        assertThat(row.errorCode()).isEqualTo("attempts_exhausted");
        assertThat(row.attempts()).isEqualTo(properties.maxAttempts());
        assertThat(row.leaseToken()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.publishedAt()).isNull();
    }

    @Test
    void failureBacksOffAndTheLastAllowedFailureQuarantines() {
        var seeded = RelayServiceFixture.seedEvent();
        var first = onlyClaim();
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(dispatchStore.markFailed(first, PublishFailure.BROKER_NACK, properties.retryDelayMillis(1),
                properties.maxAttempts())).isTrue();
        var backoff = RelayServiceFixture.dispatch(seeded.event());
        assertThat(backoff.state()).isEqualTo("PENDING");
        assertThat(backoff.attempts()).isEqualTo(1);
        assertThat(backoff.errorCode()).isEqualTo("broker_nack");
        assertThat(backoff.nextAttemptAt()).isAfter(Timestamp.from(Instant.now()));
        assertThat(dispatchStore.claimDue(properties, properties.batchSize()).events())
                .as("a backed-off row is not due yet").isEmpty();

        RelayServiceFixture.worker().update(
                "UPDATE outbox_dispatch SET attempts = ?, next_attempt_at = now() - interval '1 second' "
                        + "WHERE event_id = ?", properties.maxAttempts() - 1, seeded.event());
        var last = onlyClaim();
        assertThat(last.attempt()).isEqualTo(properties.maxAttempts());
        assertThat(dispatchStore.markFailed(last, PublishFailure.PUBLISH_TIMEOUT, 1_000,
                properties.maxAttempts())).isTrue();
        var terminal = RelayServiceFixture.dispatch(seeded.event());
        assertThat(terminal.state()).isEqualTo("QUARANTINED");
        assertThat(terminal.errorCode()).isEqualTo("publish_timeout");
        assertThat(terminal.leaseToken()).isNull();
        assertThat(terminal.leaseUntil()).isNull();
    }

    @Test
    void completionBlockedPastLeaseExpiryCannotPublishAfterTheLockIsReleased() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        var claim = dispatchStore.claimDue(shortLeaseProperties(), 1).events().get(0);
        assertThat(claim.attempt()).isEqualTo(1);

        ExecutorService completion = Executors.newSingleThreadExecutor();
        try (Connection blocker = RelayServiceFixture.workerConnection();
             Connection observer = RelayServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockDispatchRow(blocker, seeded.event());
            int blockerPid = backendPid(blocker);

            Future<Boolean> published = completion.submit(() -> dispatchStore.markPublished(claim));
            int waitingPid = awaitWorkerWaitingBehind(observer, blockerPid);
            assertThat(waitingPid).as("the completion must be observed waiting behind the exact row lock")
                    .isGreaterThan(0);
            assertThat(waitingPid).isNotEqualTo(blockerPid);
            awaitDatabaseClockPastLease(observer, seeded.event());

            // Releases the row lock without changing the row version, so only the live database
            // clock evaluated at the state mutation can still fence this completion.
            blocker.rollback();

            assertThat(published.get(10, TimeUnit.SECONDS))
                    .as("a completion that waited past its lease expiry must not write using the time "
                            + "it was queued")
                    .isFalse();
        } finally {
            completion.shutdownNow();
        }
        var row = RelayServiceFixture.dispatch(seeded.event());
        assertThat(row.state()).isEqualTo("LEASED");
        assertThat(row.leaseToken()).isEqualTo(claim.leaseToken());
        assertThat(row.publishedAt()).isNull();
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void failureSaveBlockedPastLeaseExpiryCannotScheduleBackoffAfterTheLockIsReleased() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        var claim = dispatchStore.claimDue(shortLeaseProperties(), 1).events().get(0);

        ExecutorService completion = Executors.newSingleThreadExecutor();
        try (Connection blocker = RelayServiceFixture.workerConnection();
             Connection observer = RelayServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockDispatchRow(blocker, seeded.event());
            int blockerPid = backendPid(blocker);

            Future<Boolean> saved = completion.submit(() -> dispatchStore.markFailed(
                    claim, PublishFailure.BROKER_NACK, 1_000, shortLeaseProperties().maxAttempts()));
            int waitingPid = awaitWorkerWaitingBehind(observer, blockerPid);
            assertThat(waitingPid).as("the failure save must be observed waiting behind the exact row lock")
                    .isGreaterThan(0);
            awaitDatabaseClockPastLease(observer, seeded.event());

            blocker.rollback();

            assertThat(saved.get(10, TimeUnit.SECONDS))
                    .as("a failure save that waited past its lease expiry must not reschedule work")
                    .isFalse();
        } finally {
            completion.shutdownNow();
        }
        var row = RelayServiceFixture.dispatch(seeded.event());
        assertThat(row.state()).isEqualTo("LEASED");
        assertThat(row.errorCode()).isNull();
        assertThat(row.leaseToken()).isEqualTo(claim.leaseToken());
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void lockedExhaustedRowDoesNotStallClaimsOfUnrelatedDueWork() throws Exception {
        var exhausted = RelayServiceFixture.seedEvent();
        var eligible = RelayServiceFixture.seedEvent();
        RelayServiceFixture.worker().update(
                "UPDATE outbox_dispatch SET attempts = ?, next_attempt_at = now() - interval '1 second' "
                        + "WHERE event_id = ?", properties.maxAttempts(), exhausted.event());

        ExecutorService claimer = Executors.newSingleThreadExecutor();
        Future<DispatchStore.ClaimResult> claimed = null;
        Connection blocker = RelayServiceFixture.workerConnection();
        Connection observer = RelayServiceFixture.workerConnection();
        try (blocker; observer) {
            blocker.setAutoCommit(false);
            lockDispatchRow(blocker, exhausted.event());
            int blockerPid = backendPid(blocker);

            claimed = claimer.submit(() -> dispatchStore.claimDue(properties, properties.batchSize()));

            int observedBlocked = -1;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!claimed.isDone() && System.nanoTime() < deadline) {
                observedBlocked = waitingPidBehind(observer, blockerPid);
                if (observedBlocked > 0) {
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(observedBlocked)
                    .as("the claim path must never wait on another transaction's row lock "
                            + "(pg_blocking_pids observed pid=%s blocked by %s)", observedBlocked, blockerPid)
                    .isEqualTo(-1);
            var result = claimed.get(10, TimeUnit.SECONDS);
            assertThat(result.events()).extracting(ClaimedEvent::eventId).containsExactly(eligible.event());
            assertThat(result.quarantined()).as("a locked exhausted row is skipped, not swept").isZero();
            assertThat(RelayServiceFixture.dispatch(exhausted.event()).state()).isEqualTo("PENDING");

            blocker.rollback();

            var afterRelease = dispatchStore.claimDue(properties, properties.batchSize());
            assertThat(afterRelease.quarantined()).as("bounded quarantine progress resumes after the release")
                    .isEqualTo(1);
        } finally {
            try {
                blocker.rollback();
            } catch (SQLException ignored) {
                // The connection close in the try-with-resources still releases any held lock.
            }
            if (claimed != null) {
                try {
                    claimed.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Only the deliberately red baseline run leaves the claim blocked until release.
                }
            }
            claimer.shutdown();
        }
        var terminal = RelayServiceFixture.dispatch(exhausted.event());
        assertThat(terminal.state()).isEqualTo("QUARANTINED");
        assertThat(terminal.errorCode()).isEqualTo("attempts_exhausted");
        assertThat(terminal.attempts()).isEqualTo(properties.maxAttempts());
        assertThat(terminal.leaseToken()).isNull();
    }

    private ClaimedEvent onlyClaim() {
        var result = dispatchStore.claimDue(properties, 1);
        assertThat(result.events()).hasSize(1);
        return result.events().get(0);
    }

    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** A lease short enough to observe expiry in a running test while staying inside lock_timeout. */
    private static RelayProperties shortLeaseProperties() {
        return new RelayProperties(2, 4, Duration.ofSeconds(3), Duration.ofSeconds(2), 5,
                Duration.ofMillis(500), 2.0, Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    private static void lockDispatchRow(Connection connection, UUID eventId) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT event_id FROM outbox_dispatch WHERE event_id = ? FOR UPDATE")) {
            statement.setObject(1, eventId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the exact dispatch row must exist to be locked").isTrue();
            }
        }
    }

    /** Returns the backend waiting behind {@code blockerPid}, or -1 when nothing is waiting yet. */
    private static int waitingPidBehind(Connection observer, int blockerPid) throws SQLException {
        try (var statement = observer.prepareStatement("""
                SELECT pid FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND pg_blocking_pids(pid) @> ARRAY[?]::int[]
                """)) {
            statement.setInt(1, blockerPid);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    private static int awaitWorkerWaitingBehind(Connection observer, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int pid = waitingPidBehind(observer, blockerPid);
            if (pid > 0) {
                return pid;
            }
            Thread.sleep(25);
        }
        return -1;
    }

    private static void awaitDatabaseClockPastLease(Connection observer, UUID eventId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (var statement = observer.prepareStatement(
                    "SELECT clock_timestamp() > (SELECT lease_until FROM outbox_dispatch WHERE event_id = ?)")) {
                statement.setObject(1, eventId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("the database clock never passed the lease expiry for " + eventId);
    }
}

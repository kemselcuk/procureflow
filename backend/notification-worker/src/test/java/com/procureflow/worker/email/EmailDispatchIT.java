package com.procureflow.worker.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.config.SmtpProperties;
import com.procureflow.worker.support.EmailIntegrationTestBase;
import com.procureflow.worker.support.EmailServiceFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Claim, lease, fencing, eligibility and quarantine semantics against real PostgreSQL 18.6 through
 * the worker login. Every concurrency case uses independent connections and observed row locks,
 * never sleeps; Mailpit observes that ineligible work produced no message.
 */
class EmailDispatchIT extends EmailIntegrationTestBase {

    @Test
    void claimIncrementsAttemptsWithLiveDatabaseLeaseAndGeneratedDestination() {
        var seeded = EmailServiceFixture.seedJob(true);
        var initial = EmailServiceFixture.job(seeded.job());
        assertThat(initial.state()).isEqualTo("PENDING");
        assertThat(initial.attempts()).isZero();
        assertThat(initial.leaseToken()).isNull();
        assertThat(initial.recipientAddress()).isEqualTo(seeded.recipientAddress());

        var claim = onlyClaim(properties);
        assertThat(claim.jobId()).isEqualTo(seeded.job());
        assertThat(claim.companyId()).isEqualTo(seeded.company());
        assertThat(claim.identityId()).isEqualTo(seeded.identity());
        assertThat(claim.membershipId()).isEqualTo(seeded.membership());
        assertThat(claim.eventType()).isEqualTo(EmailServiceFixture.DEFAULT_EVENT_TYPE);
        assertThat(claim.requestId()).isEqualTo(seeded.request());
        assertThat(claim.correlationId()).isEqualTo(seeded.correlation());
        assertThat(claim.recipientAddress()).isEqualTo(seeded.identity() + "@demo.invalid");
        assertThat(claim.attempt()).isEqualTo(1);

        var leased = EmailServiceFixture.job(seeded.job());
        assertThat(leased.state()).isEqualTo("LEASED");
        assertThat(leased.attempts()).isEqualTo(1);
        assertThat(leased.leaseToken()).isEqualTo(claim.leaseToken());
        assertThat(leased.leaseUntil()).isAfter(Timestamp.from(Instant.now()));
    }

    @Test
    void claimSkipsFutureBackoffAndUnexpiredLeasesThenReclaimsExpiredOnes() {
        var backoff = EmailServiceFixture.seedJob(true);
        var leased = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.worker().update(
                "UPDATE email_job SET attempts = 1, next_attempt_at = now() + interval '1 hour' WHERE id = ?",
                backoff.job());
        EmailServiceFixture.worker().update(
                "UPDATE email_job SET state = 'LEASED', attempts = 1, lease_token = ?, "
                        + "lease_until = now() + interval '1 hour' WHERE id = ?",
                UUID.randomUUID(), leased.job());

        assertThat(emailJobStore.claimOne(properties).claimed())
                .as("neither backed-off nor unexpired-leased work may be claimed").isNull();

        EmailServiceFixture.expireLease(leased.job());
        EmailServiceFixture.makeDue(backoff.job());
        var first = onlyClaim(properties);
        assertThat(first.attempt()).isEqualTo(2);
        var second = onlyClaim(properties);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(List.of(first.jobId(), second.jobId()))
                .containsExactlyInAnyOrder(backoff.job(), leased.job());
        assertThat(first.leaseToken()).isNotEqualTo(second.leaseToken());
    }

    @Test
    void wrongAndStaleOwnersCannotCompleteAndAReclaimReplacesTheToken() {
        var seeded = EmailServiceFixture.seedJob(true);
        var first = onlyClaim(properties);

        var impostor = new ClaimedEmailJob(first.jobId(), first.companyId(), first.eventId(), first.identityId(),
                first.membershipId(), first.recipientAddress(), first.attempt(), UUID.randomUUID(),
                first.eventType(), first.requestId(), first.correlationId());
        assertThat(emailJobStore.markSent(impostor)).as("a foreign token must never complete work").isFalse();
        assertThat(emailJobStore.markFailed(impostor, EmailFailure.SMTP_UNREACHABLE, 1_000,
                properties.maxAttempts())).isFalse();
        assertThat(EmailServiceFixture.job(seeded.job()).state()).isEqualTo("LEASED");

        EmailServiceFixture.expireLease(seeded.job());
        assertThat(emailJobStore.markSent(first)).as("an expired lease must not complete").isFalse();
        assertThat(emailJobStore.markFailed(first, EmailFailure.SMTP_UNREACHABLE, 1_000,
                properties.maxAttempts())).isFalse();

        var second = onlyClaim(properties);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(emailJobStore.markSent(first)).as("a reclaimed owner must not complete stale work").isFalse();
        assertThat(emailJobStore.markFailed(first, EmailFailure.SMTP_TRANSIENT, 1_000,
                properties.maxAttempts())).isFalse();

        assertThat(emailJobStore.markSent(second)).isTrue();
        var sent = EmailServiceFixture.job(seeded.job());
        assertThat(sent.state()).isEqualTo("SENT");
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.errorCode()).isNull();
        assertThat(sent.leaseToken()).isNull();
        assertThat(sent.leaseUntil()).isNull();
        assertThat(emailJobStore.markSent(second)).as("a completed job is no longer completable").isFalse();
    }

    @Test
    void transientFailureBacksOffAndTheLastAllowedFailureQuarantines() {
        var seeded = EmailServiceFixture.seedJob(true);
        var first = onlyClaim(properties);
        assertThat(emailJobStore.markFailed(first, EmailFailure.SMTP_UNREACHABLE,
                properties.retryDelayMillis(1), properties.maxAttempts())).isTrue();
        var backoff = EmailServiceFixture.job(seeded.job());
        assertThat(backoff.state()).isEqualTo("PENDING");
        assertThat(backoff.attempts()).isEqualTo(1);
        assertThat(backoff.errorCode()).isEqualTo("smtp_unreachable");
        assertThat(backoff.nextAttemptAt()).isAfter(Timestamp.from(Instant.now()));
        assertThat(emailJobStore.claimOne(properties).claimed()).as("a backed-off job is not due yet").isNull();

        EmailServiceFixture.worker().update(
                "UPDATE email_job SET attempts = ?, next_attempt_at = now() - interval '1 second' WHERE id = ?",
                properties.maxAttempts() - 1, seeded.job());
        var last = onlyClaim(properties);
        assertThat(last.attempt()).isEqualTo(properties.maxAttempts());
        assertThat(emailJobStore.markFailed(last, EmailFailure.SMTP_TIMEOUT, 1_000,
                properties.maxAttempts())).isTrue();
        var terminal = EmailServiceFixture.job(seeded.job());
        assertThat(terminal.state()).isEqualTo("QUARANTINED");
        assertThat(terminal.errorCode()).isEqualTo("smtp_timeout");
        assertThat(terminal.leaseToken()).isNull();
    }

    @Test
    void permanentRejectionQuarantinesImmediatelyWithoutRetry() {
        var seeded = EmailServiceFixture.seedJob(true);
        var claim = onlyClaim(properties);
        assertThat(emailJobStore.markFailed(claim, EmailFailure.SMTP_REJECTED, 999_999,
                properties.maxAttempts())).isTrue();
        var terminal = EmailServiceFixture.job(seeded.job());
        assertThat(terminal.state()).isEqualTo("QUARANTINED");
        assertThat(terminal.errorCode()).isEqualTo("smtp_rejected");
        assertThat(terminal.attempts()).isEqualTo(1);
        assertThat(terminal.leaseToken()).isNull();
    }

    @Test
    void twoClaimersOnIndependentConnectionsTakeDisjointRowsAndTokens() throws Exception {
        List<UUID> seeded = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            seeded.add(EmailServiceFixture.seedJob(true).job());
        }
        List<ClaimedEmailJob> firstClaims = new ArrayList<>();
        List<ClaimedEmailJob> secondClaims = new ArrayList<>();
        try (Connection first = EmailServiceFixture.workerConnection();
             Connection second = EmailServiceFixture.workerConnection()) {
            assertThat(backendPid(first)).as("independent connections must use distinct backends")
                    .isNotEqualTo(backendPid(second));
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            // The first transaction holds FOR UPDATE SKIP LOCKED row locks until it commits; the
            // second transaction still sees the pre-claim PENDING rows in its own snapshot.
            firstClaims.add(claimOn(first));
            firstClaims.add(claimOn(first));
            secondClaims.add(claimOn(second));
            secondClaims.add(claimOn(second));
            first.commit();
            second.commit();
        }
        List<UUID> firstIds = firstClaims.stream().map(ClaimedEmailJob::jobId).toList();
        List<UUID> secondIds = secondClaims.stream().map(ClaimedEmailJob::jobId).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        List<UUID> combined = new ArrayList<>(firstIds);
        combined.addAll(secondIds);
        assertThat(combined).containsExactlyInAnyOrderElementsOf(seeded);
        assertThat(firstClaims.stream().map(ClaimedEmailJob::leaseToken).toList()).doesNotHaveDuplicates();
        assertThat(secondClaims.stream().map(ClaimedEmailJob::leaseToken).toList()).doesNotHaveDuplicates();
        for (UUID job : seeded) {
            var row = EmailServiceFixture.job(job);
            assertThat(row.state()).isEqualTo("LEASED");
            assertThat(row.attempts()).isEqualTo(1);
            assertThat(row.leaseToken()).isNotNull();
        }
    }

    @Test
    void completionBlockedPastLeaseExpiryCannotMarkSentAfterTheLockIsReleased() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        var claim = onlyClaim(shortLeaseProperties());

        ExecutorService completion = Executors.newSingleThreadExecutor();
        try (Connection blocker = EmailServiceFixture.workerConnection();
             Connection observer = EmailServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockJobRow(blocker, seeded.job());
            int blockerPid = backendPid(blocker);

            Future<Boolean> sent = completion.submit(() -> emailJobStore.markSent(claim));
            int waitingPid = awaitWorkerWaitingBehind(observer, blockerPid);
            assertThat(waitingPid).as("the completion must be observed waiting behind the exact row lock")
                    .isGreaterThan(0);
            awaitDatabaseClockPastLease(observer, seeded.job());

            blocker.rollback();

            assertThat(sent.get(10, TimeUnit.SECONDS))
                    .as("a completion that waited past its lease expiry must not write using the time it was queued")
                    .isFalse();
        } finally {
            completion.shutdownNow();
        }
        var row = EmailServiceFixture.job(seeded.job());
        assertThat(row.state()).isEqualTo("LEASED");
        assertThat(row.leaseToken()).isEqualTo(claim.leaseToken());
        assertThat(row.sentAt()).isNull();
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void failureSaveBlockedPastLeaseExpiryCannotScheduleBackoffAfterTheLockIsReleased() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        var claim = onlyClaim(shortLeaseProperties());

        ExecutorService completion = Executors.newSingleThreadExecutor();
        try (Connection blocker = EmailServiceFixture.workerConnection();
             Connection observer = EmailServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockJobRow(blocker, seeded.job());
            int blockerPid = backendPid(blocker);

            Future<Boolean> saved = completion.submit(() -> emailJobStore.markFailed(claim,
                    EmailFailure.SMTP_UNREACHABLE, 1_000, shortLeaseProperties().maxAttempts()));
            int waitingPid = awaitWorkerWaitingBehind(observer, blockerPid);
            assertThat(waitingPid).as("the failure save must be observed waiting behind the exact row lock")
                    .isGreaterThan(0);
            awaitDatabaseClockPastLease(observer, seeded.job());

            blocker.rollback();

            assertThat(saved.get(10, TimeUnit.SECONDS))
                    .as("a failure save that waited past its lease expiry must not reschedule work").isFalse();
        } finally {
            completion.shutdownNow();
        }
        var row = EmailServiceFixture.job(seeded.job());
        assertThat(row.state()).isEqualTo("LEASED");
        assertThat(row.errorCode()).isNull();
        assertThat(row.leaseToken()).isEqualTo(claim.leaseToken());
        assertThat(row.attempts()).isEqualTo(1);
    }

    @Test
    void inactiveMembershipSkipsTerminallyWithoutSmtpAndPreservesMaterializedDelivery() {
        var seeded = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.deactivateMembership(seeded.membership());
        var capturing = new CapturingEmailInstrumentation();

        runnerWith(smtpSender, capturing).runOnce();

        var row = EmailServiceFixture.job(seeded.job());
        assertThat(row.state()).isEqualTo("SKIPPED");
        assertThat(row.errorCode()).isEqualTo("recipient_inactive");
        assertThat(row.leaseToken()).isNull();
        assertThat(row.sentAt()).isNull();
        assertThat(capturing.sends()).as("no SMTP attempt may happen for an ineligible job").isEmpty();
        assertThat(EmailServiceFixture.mailpitTotal()).isZero();
        var delivery = EmailServiceFixture.delivery(seeded.delivery());
        assertThat(delivery.outcome()).as("the earlier MATERIALIZED materialization outcome is preserved")
                .isEqualTo("MATERIALIZED");
        assertThat(delivery.reasonCode()).isNull();

        // SKIPPED is terminal: replaying the due clock cannot reactivate it or send mail.
        EmailServiceFixture.makeDue(seeded.job());
        runnerWith(smtpSender, capturing).runOnce();
        assertThat(EmailServiceFixture.job(seeded.job()).state()).isEqualTo("SKIPPED");
        assertThat(EmailServiceFixture.mailpitTotal()).isZero();
    }

    @Test
    void eligibilityExaminesCapturedIdentityCompanyBindingAgainstRealRows() throws Exception {
        var owner = EmailServiceFixture.seedJob(true);
        var inactive = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.deactivateMembership(inactive.membership());
        try (Connection connection = EmailServiceFixture.workerConnection()) {
            assertThat(EmailJobStore.eligibility(connection, owner.company(), owner.membership(), owner.identity()))
                    .isEqualTo(EmailJobStore.Eligibility.ELIGIBLE);
            assertThat(EmailJobStore.eligibility(connection, inactive.company(), inactive.membership(),
                    inactive.identity())).isEqualTo(EmailJobStore.Eligibility.INACTIVE);
            assertThat(EmailJobStore.eligibility(connection, owner.company(), owner.membership(),
                    inactive.identity()))
                    .as("an identity not bound to the captured membership is a mismatch")
                    .isEqualTo(EmailJobStore.Eligibility.MISMATCH);
            assertThat(EmailJobStore.eligibility(connection, owner.company(), UUID.randomUUID(), owner.identity()))
                    .as("a missing membership row is a mismatch").isEqualTo(EmailJobStore.Eligibility.MISMATCH);
        }
    }

    @Test
    void companyBarrierIsTakenBeforeTheJobRowAndTheRevocationIsObserved() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        var capturing = new CapturingEmailInstrumentation();
        ExecutorService runner = Executors.newSingleThreadExecutor();
        // The exclusive company barrier is simulated with the maintenance login, because the worker
        // login deliberately lacks company UPDATE privilege (that is what lock_notification_company
        // exists for); the worker only ever takes the SHARE barrier inside the function.
        try (Connection blocker = EmailServiceFixture.owner().getDataSource().getConnection();
             Connection observer = EmailServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockCompanyRow(blocker, seeded.company());
            int blockerPid = backendPid(blocker);
            Future<?> running = runner.submit(() -> runnerWith(smtpSender, capturing).runOnce());

            int waitingPid = awaitWorkerWaitingBehind(observer, blockerPid);
            assertThat(waitingPid)
                    .as("the claim must wait on the company barrier before touching the delivery row")
                    .isGreaterThan(0);

            // While the claim waits on the company lock it holds no job row lock, so an observer can
            // take the exact row immediately; this proves the company-before-delivery-row order.
            lockJobRowNoWait(observer, seeded.job());

            try (var deactivate = blocker.prepareStatement("UPDATE membership SET active = false WHERE id = ?")) {
                deactivate.setObject(1, seeded.membership());
                deactivate.executeUpdate();
            }
            blocker.commit();
            running.get(20, TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
        }
        var row = EmailServiceFixture.job(seeded.job());
        assertThat(row.state()).isEqualTo("SKIPPED");
        assertThat(row.errorCode()).isEqualTo("recipient_inactive");
        assertThat(EmailServiceFixture.mailpitTotal()).isZero();
        assertThat(EmailServiceFixture.delivery(seeded.delivery()).outcome()).isEqualTo("MATERIALIZED");
    }

    @Test
    void exhaustedLockedJobDoesNotBlockUnrelatedWorkAndIsQuarantinedAfterRelease() throws Exception {
        var exhausted = EmailServiceFixture.seedJob(true);
        var eligible = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.exhaust(exhausted.job(), properties.maxAttempts());

        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (Connection blocker = EmailServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockJobRow(blocker, exhausted.job());

            Future<?> running = runner.submit(() -> emailRunner.runOnce());
            running.get(5, TimeUnit.SECONDS);

            var eligibleRow = EmailServiceFixture.job(eligible.job());
            assertThat(eligibleRow.state()).as("unrelated due work must progress past a locked exhausted row")
                    .isEqualTo("SENT");
            assertThat(EmailServiceFixture.mailpitTotal()).isEqualTo(1);
            assertThat(EmailServiceFixture.job(exhausted.job()).state())
                    .as("a locked exhausted row is skipped, not swept while locked").isEqualTo("PENDING");

            blocker.rollback();
        } finally {
            runner.shutdownNow();
        }

        emailRunner.runOnce();
        var terminal = EmailServiceFixture.job(exhausted.job());
        assertThat(terminal.state()).isEqualTo("QUARANTINED");
        assertThat(terminal.errorCode()).isEqualTo("attempts_exhausted");
        assertThat(terminal.attempts()).isEqualTo(properties.maxAttempts());
        assertThat(terminal.leaseToken()).isNull();
        assertThat(EmailServiceFixture.mailpitTotal()).as("no additional message for terminal work").isEqualTo(1);
    }

    @Test
    void crashExhaustedRowAndSkippedRowAreNotResentByOrdinaryRuns() {
        var crashed = EmailServiceFixture.seedJob(true);
        var skipped = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.exhaust(crashed.job(), properties.maxAttempts());
        EmailServiceFixture.worker().update(
                "UPDATE email_job SET state = 'SKIPPED', error_code = 'recipient_inactive' WHERE id = ?",
                skipped.job());

        emailRunner.runOnce();

        assertThat(EmailServiceFixture.job(crashed.job()).state()).isEqualTo("QUARANTINED");
        assertThat(EmailServiceFixture.job(skipped.job()).state()).isEqualTo("SKIPPED");
        assertThat(EmailServiceFixture.mailpitTotal()).isZero();
    }

    @Test
    void operatorReplayProcedureReactivatesOnlyQuarantinedJobsAndPreservesTheId() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.exhaust(seeded.job(), properties.maxAttempts());
        emailRunner.runOnce();
        assertThat(EmailServiceFixture.job(seeded.job()).state()).isEqualTo("QUARANTINED");

        // The exact operator procedure reported to the main agent; the privileged operator runs it
        // with the maintenance login, exactly as a local DBA would during incident recovery.
        try (Connection operator = EmailServiceFixture.owner().getDataSource().getConnection()) {
            operator.setAutoCommit(false);
            try (var statement = operator.prepareStatement(
                    "SELECT id FROM email_job WHERE id = ? AND company_id = ? FOR UPDATE")) {
                statement.setObject(1, seeded.job());
                statement.setObject(2, seeded.company());
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("the operator must lock the exact job").isTrue();
                }
            }
            try (var statement = operator.prepareStatement("""
                    UPDATE email_job
                    SET state = 'PENDING', attempts = 0, next_attempt_at = now(),
                        lease_token = NULL, lease_until = NULL, error_code = NULL
                    WHERE id = ? AND company_id = ? AND state = 'QUARANTINED'
                    """)) {
                statement.setObject(1, seeded.job());
                statement.setObject(2, seeded.company());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            operator.commit();
        }

        EmailServiceFixture.makeDue(seeded.job());
        emailRunner.runOnce();
        var recovered = EmailServiceFixture.job(seeded.job());
        assertThat(recovered.state()).isEqualTo("SENT");
        assertThat(recovered.deliveryId()).isEqualTo(seeded.delivery());
        assertThat(EmailServiceFixture.mailpitTotal()).isEqualTo(1);
        assertThat("<" + EmailServiceFixture.mailpitMessages().get(0).path("MessageID").asText() + ">")
                .isEqualTo(SmtpEmailSender.messageId(seeded.job()));

        // The replay predicate must never touch a SENT job.
        try (Connection operator = EmailServiceFixture.owner().getDataSource().getConnection();
             var statement = operator.prepareStatement("""
                     UPDATE email_job SET state = 'PENDING', attempts = 0, error_code = NULL
                     WHERE id = ? AND company_id = ? AND state = 'QUARANTINED'
                     """)) {
            statement.setObject(1, seeded.job());
            statement.setObject(2, seeded.company());
            assertThat(statement.executeUpdate()).as("SENT is not replayable").isZero();
        }
        assertThat(EmailServiceFixture.job(seeded.job()).state()).isEqualTo("SENT");
    }

    @Test
    void rotatingWindowReachesTheTailBehindALockedHeadAtWorkLimitOne() throws Exception {
        var locked = EmailServiceFixture.seedJob(true);
        var tail = EmailServiceFixture.seedJob(true);
        stagger(locked.job(), 2);
        stagger(tail.job(), 1);
        SmtpProperties single = workLimited(1);

        try (Connection blocker = EmailServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockJobRow(blocker, locked.job());

            assertThat(emailJobStore.claimOne(single).claimed())
                    .as("the first bounded window only sees the locked head candidate").isNull();
            var claimed = emailJobStore.claimOne(single).claimed();
            assertThat(claimed).as("the rotated window must reach the eligible tail behind a locked head")
                    .isNotNull();
            assertThat(claimed.jobId()).isEqualTo(tail.job());

            blocker.rollback();
        }

        assertThat(EmailServiceFixture.job(locked.job()).state()).isEqualTo("PENDING");
        var afterUnlock = emailJobStore.claimOne(single).claimed();
        assertThat(afterUnlock).as("the released head row is claimable once its blocker committed").isNotNull();
        assertThat(afterUnlock.jobId()).isEqualTo(locked.job());
    }

    @Test
    void rotatingWindowPassesALockedPrefixLongerThanTheWorkLimit() throws Exception {
        var lockedFirst = EmailServiceFixture.seedJob(true);
        var lockedSecond = EmailServiceFixture.seedJob(true);
        var lockedThird = EmailServiceFixture.seedJob(true);
        var tail = EmailServiceFixture.seedJob(true);
        stagger(lockedFirst.job(), 4);
        stagger(lockedSecond.job(), 3);
        stagger(lockedThird.job(), 2);
        stagger(tail.job(), 1);
        SmtpProperties pair = workLimited(2);

        ClaimedEmailJob claimed = null;
        int cycles = 0;
        try (Connection blocker = EmailServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockJobRow(blocker, lockedFirst.job());
            lockJobRow(blocker, lockedSecond.job());
            lockJobRow(blocker, lockedThird.job());

            while (claimed == null && cycles < 4) {
                claimed = emailJobStore.claimOne(pair).claimed();
                cycles++;
            }
            assertThat(claimed).as("a locked prefix longer than the window must not hide the tail")
                    .isNotNull();
            assertThat(claimed.jobId()).isEqualTo(tail.job());
            assertThat(cycles).as("bounded rotation reaches the tail within prefix/window + 1 cycles")
                    .isLessThanOrEqualTo(3);

            blocker.rollback();
        }
        assertThat(EmailServiceFixture.job(tail.job()).state()).isEqualTo("LEASED");
    }

    @Test
    void lockedExhaustedPrefixDoesNotHideTheTailNormalJob() throws Exception {
        var exhaustedFirst = EmailServiceFixture.seedJob(true);
        var exhaustedSecond = EmailServiceFixture.seedJob(true);
        var tail = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.exhaust(exhaustedFirst.job(), properties.maxAttempts());
        EmailServiceFixture.exhaust(exhaustedSecond.job(), properties.maxAttempts());
        stagger(exhaustedFirst.job(), 3);
        stagger(exhaustedSecond.job(), 2);
        stagger(tail.job(), 1);
        SmtpProperties single = workLimited(1);

        ClaimedEmailJob claimed = null;
        int cycles = 0;
        try (Connection blocker = EmailServiceFixture.workerConnection()) {
            blocker.setAutoCommit(false);
            lockJobRow(blocker, exhaustedFirst.job());
            lockJobRow(blocker, exhaustedSecond.job());

            while (claimed == null && cycles < 4) {
                claimed = emailJobStore.claimOne(single).claimed();
                cycles++;
            }
            assertThat(claimed).as("locked exhausted rows must not hide the tail normal job").isNotNull();
            assertThat(claimed.jobId()).isEqualTo(tail.job());
            assertThat(cycles).isLessThanOrEqualTo(3);
            assertThat(EmailServiceFixture.job(exhaustedFirst.job()).state())
                    .as("a locked exhausted row is skipped, not swept while locked").isEqualTo("PENDING");
            assertThat(EmailServiceFixture.job(exhaustedSecond.job()).state()).isEqualTo("PENDING");

            blocker.rollback();
        }
        assertThat(emailJobStore.markSent(claimed)).as("the tail job completes through the normal path").isTrue();
        assertThat(EmailServiceFixture.job(tail.job()).state()).isEqualTo("SENT");

        emailRunner.runOnce();
        assertThat(EmailServiceFixture.job(exhaustedFirst.job()).state()).isEqualTo("QUARANTINED");
        assertThat(EmailServiceFixture.job(exhaustedSecond.job()).state()).isEqualTo("QUARANTINED");
        assertThat(EmailServiceFixture.mailpitTotal()).as("terminal crash rows must never send mail").isZero();
    }

    /**
     * The lock order holds at every branch: once a claim attempt acquires a delivery row it ends
     * on that row (here the inline quarantine of an exhausted candidate) instead of waiting for
     * another company barrier while holding the row lock.
     */
    @Test
    void exhaustedCandidateDoesNotWaitOnAnotherCompanyBarrierWhileHoldingItsRowLock() throws Exception {
        var exhausted = EmailServiceFixture.seedJob(true);
        var next = EmailServiceFixture.seedJob(true);
        EmailServiceFixture.exhaust(exhausted.job(), properties.maxAttempts());
        stagger(exhausted.job(), 2);
        stagger(next.job(), 1);

        ExecutorService claim = Executors.newSingleThreadExecutor();
        try (Connection blocker = EmailServiceFixture.owner().getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            lockCompanyRow(blocker, next.company());

            Future<EmailJobStore.ClaimResult> result = claim.submit(() -> emailJobStore.claimOne(properties));
            var outcome = result.get(5, TimeUnit.SECONDS);
            assertThat(outcome.quarantined())
                    .as("the exhausted candidate is quarantined without waiting on the next company barrier")
                    .isEqualTo(1);
            assertThat(EmailServiceFixture.job(exhausted.job()).state()).isEqualTo("QUARANTINED");
            assertThat(EmailServiceFixture.job(next.job()).state()).isEqualTo("PENDING");

            blocker.rollback();
        } finally {
            claim.shutdownNow();
            claim.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** Same bounded runner settings, but with an explicit candidate window size. */
    private SmtpProperties workLimited(int workLimit) {
        return new SmtpProperties(true, false, properties.host(), properties.port(), properties.leaseTimeout(),
                properties.connectionTimeout(), properties.readTimeout(), properties.writeTimeout(),
                properties.maxAttempts(), properties.retryBackoff(), properties.retryBackoffMultiplier(),
                properties.retryBackoffMax(), properties.pollInterval(), workLimit, properties.cleanupBatchSize());
    }

    /** Deterministic due order: a larger value places the job earlier in the keyset. */
    private static void stagger(UUID jobId, int secondsAgo) {
        EmailServiceFixture.worker().update(
                "UPDATE email_job SET next_attempt_at = now() - make_interval(secs => ?) WHERE id = ?",
                (double) secondsAgo, jobId);
    }

    private ClaimedEmailJob onlyClaim(SmtpProperties claimProperties) {
        var result = emailJobStore.claimOne(claimProperties);
        assertThat(result.claimed()).isNotNull();
        return result.claimed();
    }

    private ClaimedEmailJob claimOn(Connection connection) {
        try {
            var result = EmailJobStore.claimOn(connection, properties);
            assertThat(result.claimed()).as("an unlocked due row must be claimable").isNotNull();
            return result.claimed();
        } catch (SQLException e) {
            throw new IllegalStateException("Claim on the test connection failed", e);
        }
    }

    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void lockCompanyRow(Connection connection, UUID companyId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id FROM company WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, companyId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the exact company row must exist to be locked").isTrue();
            }
        }
    }

    private static void lockJobRow(Connection connection, UUID jobId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id FROM email_job WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, jobId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the exact job row must exist to be locked").isTrue();
            }
        }
    }

    private static void lockJobRowNoWait(Connection connection, UUID jobId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT id FROM email_job WHERE id = ? FOR UPDATE NOWAIT")) {
            statement.setObject(1, jobId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the waiting claim must not hold the job row lock").isTrue();
            }
        }
    }

    /** Returns the backend waiting behind {@code blockerPid}, or -1 when nothing is waiting yet. */
    private static int waitingPidBehind(Connection observer, int blockerPid) throws SQLException {
        try (var statement = observer.prepareStatement("""
                SELECT pid FROM pg_stat_activity
                WHERE usename = 'procureflow_worker' AND wait_event_type = 'Lock'
                  AND pg_blocking_pids(pid) @> ARRAY[?]::int[]
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

    private static void awaitDatabaseClockPastLease(Connection observer, UUID jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (var statement = observer.prepareStatement(
                    "SELECT clock_timestamp() > (SELECT lease_until FROM email_job WHERE id = ?)")) {
                statement.setObject(1, jobId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("the database clock never passed the lease expiry for " + jobId);
    }
}

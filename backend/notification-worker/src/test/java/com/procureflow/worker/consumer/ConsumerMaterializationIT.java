package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.RelayServiceFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Real PostgreSQL 18.6 / RabbitMQ 4.3.6 materialization evidence for the consumer-only P4 slice:
 * channel outcomes, duplicate serialization through the inbox key, inactive/foreign recipient skips
 * (including an observed company-barrier race) and the restricted worker login's read boundary.
 */
class ConsumerMaterializationIT extends ConsumerIntegrationTestBase {

    @Test
    void validEventMaterializesAllChannelsBeforeTheAckAndDoesNotRequireDispatchPublished() {
        var actor = ConsumerServiceFixture.createActor("valid");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(ConsumerServiceFixture.RecipientSeed.both(member)));
        assertThat(ConsumerServiceFixture.dispatchState(seeded.event()))
                .as("the relay confirmation is not a consumer precondition").isEqualTo("PENDING");

        publish(seeded);
        await("inbox materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("acknowledged delivery", () -> ConsumerServiceFixture.queueDepth().drained());

        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isEqualTo(1);
        var deliveries = ConsumerServiceFixture.deliveries(seeded.company(), seeded.event());
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery.identityId()).isEqualTo(member.identity());
            assertThat(delivery.membershipId()).isEqualTo(member.membership());
            assertThat(delivery.outcome()).isEqualTo("MATERIALIZED");
            assertThat(delivery.reasonCode()).isNull();
        });
        assertThat(deliveries).extracting(ConsumerServiceFixture.DeliveryRow::channel)
                .containsExactlyInAnyOrder("IN_APP", "EMAIL");

        var inApp = ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event());
        assertThat(inApp).singleElement().satisfies(notification -> {
            assertThat(notification.identityId()).isEqualTo(member.identity());
            assertThat(notification.readAt()).isNull();
        });
        var jobs = ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event());
        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.identityId()).isEqualTo(member.identity());
            assertThat(job.recipientAddress()).isEqualTo(member.identity() + "@demo.invalid");
            assertThat(job.state()).isEqualTo("PENDING");
            assertThat(job.attempts()).isZero();
            assertThat(job.sentAt()).isNull();
            assertThat(job.errorCode()).isNull();
        });
        assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.dispatchState(seeded.event()))
                .as("the consumer never mutates dispatch state").isEqualTo("PENDING");
    }

    @Test
    void capturedRecipientsAreMaterializedExactlyOncePerRecipientAndChannel() {
        var actor = ConsumerServiceFixture.createActor("channels");
        var first = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var second = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.department_approved",
                List.of(ConsumerServiceFixture.RecipientSeed.both(first),
                        ConsumerServiceFixture.RecipientSeed.both(second)));

        publish(seeded);
        await("inbox materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("acknowledged delivery", () -> ConsumerServiceFixture.queueDepth().drained());

        var deliveries = ConsumerServiceFixture.deliveries(seeded.company(), seeded.event());
        assertThat(deliveries).hasSize(4);
        assertThat(deliveries).filteredOn(row -> row.identityId().equals(first.identity()))
                .extracting(ConsumerServiceFixture.DeliveryRow::channel)
                .containsExactlyInAnyOrder("IN_APP", "EMAIL");
        assertThat(deliveries).filteredOn(row -> row.identityId().equals(second.identity()))
                .extracting(ConsumerServiceFixture.DeliveryRow::channel)
                .containsExactlyInAnyOrder("IN_APP", "EMAIL");
        assertThat(deliveries).allSatisfy(row -> assertThat(row.outcome()).isEqualTo("MATERIALIZED"));
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event()))
                .extracting(ConsumerServiceFixture.InAppRow::identityId)
                .containsExactlyInAnyOrder(first.identity(), second.identity());
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event()))
                .extracting(ConsumerServiceFixture.EmailJobRow::identityId)
                .containsExactlyInAnyOrder(first.identity(), second.identity());
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event()))
                .allSatisfy(job -> assertThat(job.state()).isEqualTo("PENDING"));
    }

    @Test
    void eventWithoutCapturedRecipientsCommitsTheInboxMarkerOnly() {
        var actor = ConsumerServiceFixture.createActor("empty");
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted", List.of());

        publish(seeded);
        await("inbox materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("acknowledged delivery", () -> ConsumerServiceFixture.queueDepth().drained());

        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isEqualTo(1);
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).isEmpty();
    }

    @Test
    void independentSimultaneousDuplicatesCommitOneInboxAndOneSetOfEffects() throws Exception {
        var actor = ConsumerServiceFixture.createActor("duplicate");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(ConsumerServiceFixture.RecipientSeed.both(member)));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService publishers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = publishers.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                publish(seeded);
                return null;
            });
            Future<?> second = publishers.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                publish(seeded);
                return null;
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            publishers.shutdownNow();
        }

        await("inbox materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("both duplicate deliveries acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event()))
                .as("the inbox unique key serializes independent consumers").isEqualTo(1);
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2);
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).hasSize(1);
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).hasSize(1);
        assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).isEmpty();
    }

    @Test
    void inactiveCapturedRecipientIsSkippedWithAFixedReasonAndNeverRerouted() {
        var actor = ConsumerServiceFixture.createActor("inactive");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(ConsumerServiceFixture.RecipientSeed.both(member)));
        // The person is now active only in another company; the captured membership here is inactive.
        ConsumerServiceFixture.setMembershipActive(member.membership(), false);
        var otherCompany = ConsumerServiceFixture.createCompany("reassigned");
        var otherDepartment = ConsumerServiceFixture.createDepartment(otherCompany, "Finance");
        ConsumerServiceFixture.createMembershipForIdentity(otherCompany, otherDepartment, member.identity(), true);

        publish(seeded);
        await("inbox materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("acknowledged delivery", () -> ConsumerServiceFixture.queueDepth().drained());

        var deliveries = ConsumerServiceFixture.deliveries(seeded.company(), seeded.event());
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery.outcome()).isEqualTo("SKIPPED");
            assertThat(delivery.reasonCode()).isEqualTo("membership_inactive");
            assertThat(delivery.membershipId()).isEqualTo(member.membership());
        });
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.countByCompany("notification_inbox", otherCompany))
                .as("a captured identity must never be rerouted to its new company").isZero();
        assertThat(ConsumerServiceFixture.countByCompany("notification_delivery", otherCompany)).isZero();
    }

    @Test
    void membershipDeactivatedBehindTheObservedCompanyBarrierIsSkipped() throws Exception {
        var actor = ConsumerServiceFixture.createActor("barrier");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(ConsumerServiceFixture.RecipientSeed.both(member)));

        int adminPid;
        try (Connection admin = RelayServiceFixture.owner().getDataSource().getConnection()) {
            admin.setAutoCommit(false);
            try (PreparedStatement lock = admin.prepareStatement("SELECT id FROM company WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, actor.company());
                try (ResultSet rows = lock.executeQuery()) {
                    assertThat(rows.next()).as("the company row must exist to hold the barrier").isTrue();
                }
            }
            try (PreparedStatement deactivate = admin.prepareStatement(
                    "UPDATE membership SET active = false WHERE id = ?")) {
                deactivate.setObject(1, member.membership());
                assertThat(deactivate.executeUpdate()).isEqualTo(1);
            }
            adminPid = backendPid(admin);

            publish(seeded);
            int blockedPid = awaitWorkerWaitingBehind(adminPid);
            assertThat(blockedPid)
                    .as("the consumer must wait on the company barrier before revalidating membership")
                    .isGreaterThan(0)
                    .isNotEqualTo(adminPid);

            admin.commit();
        }

        await("inbox materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("acknowledged delivery", () -> ConsumerServiceFixture.queueDepth().drained());

        var deliveries = ConsumerServiceFixture.deliveries(seeded.company(), seeded.event());
        assertThat(deliveries).hasSize(2).allSatisfy(delivery -> {
            assertThat(delivery.outcome()).isEqualTo("SKIPPED");
            assertThat(delivery.reasonCode()).isEqualTo("membership_inactive");
        });
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();
    }

    @Test
    void workerLoginCannotReadPurchaseBudgetIdentityOrRoleData() {
        for (String table : List.of("purchase_request", "monthly_budget", "budget_charge", "approval_step",
                "app_identity", "membership_role", "request_history", "audit_entry", "api_command")) {
            assertThatThrownBy(() -> RelayServiceFixture.worker().queryForList("SELECT * FROM " + table))
                    .as("worker must not read %s", table)
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("permission denied");
        }
        assertThatThrownBy(() -> RelayServiceFixture.worker().queryForList("SELECT * FROM membership"))
                .as("membership access is limited to the four worker-granted columns")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        assertThat(RelayServiceFixture.worker().queryForObject(
                "SELECT count(*) FROM membership", Long.class)).isZero();
        assertThat(RelayServiceFixture.worker().queryForObject(
                "SELECT count(*) FROM outbox_event", Long.class)).isZero();
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Returns the worker backend waiting behind {@code blockerPid}, or fails after the deadline. */
    private static int awaitWorkerWaitingBehind(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observer = RelayServiceFixture.workerConnection()) {
            while (System.nanoTime() < deadline) {
                try (PreparedStatement statement = observer.prepareStatement("""
                        SELECT pid FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND pg_blocking_pids(pid) @> ARRAY[?]::int[]
                        """)) {
                    statement.setInt(1, blockerPid);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (rows.next()) {
                            return rows.getInt(1);
                        }
                    }
                }
                Thread.sleep(25);
            }
        }
        return -1;
    }
}

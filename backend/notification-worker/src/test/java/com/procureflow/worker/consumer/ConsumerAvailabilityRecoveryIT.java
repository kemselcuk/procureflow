package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.ConsumerServiceFixture.RecipientSeed;
import com.procureflow.worker.support.RelayServiceFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M2 regression on real PostgreSQL and RabbitMQ: while the worker cannot commit materialization
 * effects and cannot read or write diagnostic/attempt evidence (a real database privilege fault,
 * not a mock), both consumers fill their complete prefetch window with deliveries that can neither
 * commit effects nor commit terminal evidence. The database access is then restored without any
 * worker, channel or container restart; every retained delivery and every later queued message must
 * still progress and be acknowledged exactly once.
 */
class ConsumerAvailabilityRecoveryIT extends ConsumerIntegrationTestBase {
    private static final Duration OUTAGE_AWAIT = Duration.ofSeconds(30);
    /**
     * The local attempt budget (five bounded attempts with backoff) is exhausted within roughly
     * four seconds per delivery under this fault, so this window leaves at least the first
     * deliveries of both consumers in the retained state that must recover without a restart.
     */
    private static final Duration OUTAGE_HOLD = Duration.ofSeconds(10);
    private static final Duration RECOVERY_AWAIT = Duration.ofSeconds(30);
    private static final int MESSAGES = 10;
    private static final int PREFETCH_WINDOW = 8;

    @Test
    void databaseFaultDisablingEffectsAndEvidenceRecoversWithoutRestart() {
        var actor = ConsumerServiceFixture.createActor("outage");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        List<ConsumerServiceFixture.SeededEvent> seeded = new ArrayList<>(MESSAGES);
        for (int index = 0; index < MESSAGES; index++) {
            seeded.add(ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                    List.of(RecipientSeed.both(member))));
        }

        ConsumerServiceFixture.suspendDatabaseAccess();
        try {
            for (ConsumerServiceFixture.SeededEvent event : seeded) {
                publish(event);
            }
            // Two concurrent consumers, prefetch 4 each: the whole window fills with unacknowledged
            // deliveries and the remaining messages stay ready. Nothing may be acknowledged.
            ConsumerServiceFixture.await(OUTAGE_AWAIT, "both prefetch windows filled", () -> {
                var depth = ConsumerServiceFixture.queueDepth();
                return depth.unacknowledged() >= PREFETCH_WINDOW && depth.ready() >= 1;
            });
            sleep(OUTAGE_HOLD);
            var held = ConsumerServiceFixture.queueDepth();
            assertThat(held.drained())
                    .as("no delivery may be acknowledged while neither effects nor evidence can commit")
                    .isFalse();
            assertThat(held.unacknowledged()).as("the prefetch window must stay held")
                    .isGreaterThanOrEqualTo(PREFETCH_WINDOW);
            assertThat(inboxRowsAsOwner(actor.company())).as("no effect may commit during the outage").isZero();

            // The database access returns; the retained deliveries retry within their capped backoff
            // and the listener keeps running throughout.
            ConsumerServiceFixture.restoreDatabaseAccess();
            ConsumerServiceFixture.await(RECOVERY_AWAIT, "all events materialize after the database returns",
                    () -> seeded.stream().allMatch(event ->
                            ConsumerServiceFixture.inbox(event.company(), event.event()).isPresent()));
            ConsumerServiceFixture.await(RECOVERY_AWAIT, "every delivery acknowledged",
                    () -> ConsumerServiceFixture.queueDepth().drained());

            for (ConsumerServiceFixture.SeededEvent event : seeded) {
                assertThat(ConsumerServiceFixture.count("notification_inbox", event.company(), event.event()))
                        .as("exactly one inbox marker per event").isEqualTo(1);
                assertThat(ConsumerServiceFixture.deliveries(event.company(), event.event()))
                        .as("both channels materialize exactly once").hasSize(2);
                assertThat(ConsumerServiceFixture.inAppNotifications(event.company(), event.event())).hasSize(1);
                assertThat(ConsumerServiceFixture.emailJobs(event.company(), event.event())).hasSize(1);
                assertThat(ConsumerServiceFixture.diagnostics(event.company(), event.event()))
                        .as("recovery must materialize instead of quarantining").isEmpty();
            }
            assertThat(listenerRegistry.getListenerContainer(NotificationQueueListener.LISTENER_ID).isRunning())
                    .as("recovery must not require a listener/worker restart").isTrue();
        } finally {
            ConsumerServiceFixture.restoreDatabaseAccess();
            // Leave the shared context without held deliveries even if an assertion failed above; this
            // cleanup runs after the recovery assertions and must never mask the real failure.
            try {
                stopListener();
                startListener();
                await("queue cleanup after the outage test", () -> ConsumerServiceFixture.queueDepth().drained());
            } catch (RuntimeException | AssertionError ignored) {
                // The next test's reset purges the queue; cleanup is best-effort only.
            }
        }
    }

    private static long inboxRowsAsOwner(java.util.UUID company) {
        Long count = RelayServiceFixture.owner().queryForObject(
                "SELECT count(*) FROM notification_inbox WHERE company_id = ?", Long.class, company);
        return count == null ? 0 : count;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while observing the database outage", e);
        }
    }
}

package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.config.RabbitTopology;
import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.ConsumerServiceFixture.RecipientSeed;
import com.procureflow.worker.support.RelayServiceFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real PostgreSQL/RabbitMQ recovery evidence: a failed materialization transaction rolls back every
 * effect, the sanitized diagnostic is the durable quarantine record after the bounded attempt cap,
 * a transient failure recovers within the in-process retry, a crash after the commit is recovered by
 * the listener's retained-delivery retry without duplicate effects, a later real redelivery keeps
 * read/sent state, and exhausted poison is observed across a restart without re-materializing. The
 * documented privileged replay re-drives the stable event through the real relay after the operator
 * correction.
 */
class ConsumerRecoveryIT extends ConsumerIntegrationTestBase {

    @Test
    void injectedInsertFailureRollsBackEveryEffectThenQuarantineAndReplayRecover() {
        var actor = ConsumerServiceFixture.createActor("rollback");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        ConsumerServiceFixture.revokeInsert("email_job");
        try {
            publish(seeded);
            // The first channel (IN_APP) is inserted before the failing EMAIL job insert, so a
            // successful rollback must also remove the earlier delivery/in-app rows.
            await("bounded exhaustion record", () -> attemptCount(seeded) == properties.maxAttempts());
            await("exhausted delivery acknowledged after its quarantine record",
                    () -> ConsumerServiceFixture.queueDepth().drained());

            assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event()))
                    .as("the inbox marker must roll back with the failed transaction").isZero();
            assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
            assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
            assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();

            var diagnostic = ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).get(0);
            assertThat(diagnostic.errorCode()).isEqualTo("materialization_failed");
            assertThat(diagnostic.payloadHash()).isEqualTo(ConsumerFailureFingerprint.forEvent(
                    seeded.company(), seeded.event(), ConsumerErrorCode.MATERIALIZATION_FAILED));
            assertThat(diagnostic.attempts()).isEqualTo(properties.maxAttempts());

            // A redelivery after a worker restart consults the durable attempt evidence and does not
            // hammer materialization again; it records the observation once and acknowledges.
            stopListener();
            startListener();
            publish(seeded);
            await("observed exhausted redelivery", () -> attemptCount(seeded) == properties.maxAttempts() + 1);
            await("observed redelivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
            assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event()))
                    .as("no effect may appear for an exhausted event").isZero();
            assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
        } finally {
            ConsumerServiceFixture.grantInsert("email_job");
        }

        // Documented privileged replay: explicit operator reset clears the event's quarantine rows,
        // then the stable canonical event is re-driven through the real relay (same event id).
        ConsumerServiceFixture.deleteDiagnostics(seeded.company(), seeded.event());
        ConsumerServiceFixture.resetDispatch(seeded.event());
        relay.runOnce();

        await("replayed materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("replayed delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2);
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).hasSize(1);
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).singleElement()
                .satisfies(job -> assertThat(job.state()).isEqualTo("PENDING"));
        assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).isEmpty();
    }

    @Test
    void transientInsertFailureRecoversInsideTheBoundedRetry() {
        var actor = ConsumerServiceFixture.createActor("transient");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        ConsumerServiceFixture.revokeInsert("notification_inbox");
        try {
            publish(seeded);
            await("first transient attempt recorded", () -> attemptCount(seeded) >= 1);
        } finally {
            ConsumerServiceFixture.grantInsert("notification_inbox");
        }

        await("recovered materialization", () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
        await("recovered delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2);
        assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).hasSize(1);
        assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).hasSize(1);
        int attempts = attemptCount(seeded);
        assertThat(attempts).as("recovery happened before the terminal attempt cap")
                .isBetween(1, properties.maxAttempts() - 1);
        assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).singleElement()
                .satisfies(row -> assertThat(row.errorCode()).isEqualTo("materialization_failed"));
    }

    @Test
    void crashAfterCommitIsRetriedWithoutDuplicateEffectsAndRedeliveryPreservesState() {
        var actor = ConsumerServiceFixture.createActor("ack-crash");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        assertThat(instrumentation).as("the test crash hook must replace the production no-op")
                .isNotSameAs(ConsumerInstrumentation.NOOP);
        failAfterNextMaterializationCommit();

        try {
            publish(seeded);
            await("committed effects before the lost ACK",
                    () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent()
                            && ConsumerServiceFixture.deliveries(seeded.company(), seeded.event()).size() == 2);
            // The listener retains the delivery after the simulated crash and retries it; the committed
            // inbox marker turns the retry into a duplicate-suppressed ACK without any restart.
            await("crashed delivery recovered and acknowledged",
                    () -> ConsumerServiceFixture.queueDepth().drained());

            Instant readAt = ConsumerServiceFixture.markInAppRead(seeded.company(), member.identity());
            Instant sentAt = ConsumerServiceFixture.markEmailSent(seeded.company(), member.identity());
            var jobBefore = ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event()).get(0);
            assertThat(jobBefore.state()).isEqualTo("SENT");

            // A later real redelivery of the same event must not duplicate, reset or rewrite any effect.
            await("broker delivery statistics collected", () -> wireDeliveries() >= 1);
            long deliveriesBefore = wireDeliveries();
            publish(seeded);
            await("duplicate delivery observed on the wire", () -> wireDeliveries() > deliveriesBefore);
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
            assertThat(ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event())).isEmpty();
        } finally {
            // Leave the shared context without a held delivery even if an assertion failed above; this
            // restart-cleanup runs after the recovery assertions and must not mask the real failure.
            try {
                stopListener();
                startListener();
                await("delivery cleanup after the crash test",
                        () -> ConsumerServiceFixture.queueDepth().drained());
            } catch (RuntimeException | AssertionError ignored) {
                // Best-effort cleanup only; the next test's reset purges the queue.
            }
        }
    }

    /** Cumulative broker deliveries for the queue, from the management stats counter. */
    private static long wireDeliveries() {
        var queue = RelayServiceFixture.management("GET", "/queues/%2F/" + RabbitTopology.QUEUE);
        var stats = queue.get("message_stats");
        var deliverGet = stats == null ? null : stats.get("deliver_get");
        return deliverGet == null ? 0 : deliverGet.asLong();
    }

    private int attemptCount(ConsumerServiceFixture.SeededEvent seeded) {
        return ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).stream()
                .mapToInt(ConsumerServiceFixture.DiagnosticRow::attempts).sum();
    }
}

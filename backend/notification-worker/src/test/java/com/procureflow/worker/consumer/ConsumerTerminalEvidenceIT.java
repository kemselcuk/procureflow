package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.ConsumerServiceFixture.RecipientSeed;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * H1 regression on real PostgreSQL and RabbitMQ: when the last diagnostic write also fails, the
 * durable attempt counter stops below the configured cap. That state is not terminal evidence, so
 * the delivery must stay unacknowledged (wire-level check through the queue counters) with no inbox
 * or effect rows; the in-process budget running out must never be turned into an ACK. After the
 * fault is lifted, the same unacknowledged delivery either completes its effects or commits the
 * final quarantine record without loss.
 */
class ConsumerTerminalEvidenceIT extends ConsumerIntegrationTestBase {
    private static final Duration HOLD_WINDOW = Duration.ofSeconds(3);

    @Test
    void lastDiagnosticWriteFailureKeepsTheDeliveryUnacknowledgedUntilItCanComplete() {
        var actor = ConsumerServiceFixture.createActor("terminal-complete");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        ConsumerServiceFixture.revokeInsert("email_job");
        ConsumerServiceFixture.failDiagnosticWritesAtAttempt(properties.maxAttempts());
        try {
            publish(seeded);
            // Attempts 1..4 commit; the attempt that would reach the cap is rejected, so the durable
            // counter stops one below the cap while the local budget keeps failing.
            await("committed attempts below the cap", () -> attemptCount(seeded) == properties.maxAttempts() - 1);
            assertDeliveryHeldWithoutEffects(seeded);

            // Recovery: the next retained-delivery cycle materializes again and acknowledges after the
            // effects commit; no worker restart is involved.
            ConsumerServiceFixture.grantInsert("email_job");
            await("effects committed after recovery",
                    () -> ConsumerServiceFixture.inbox(seeded.company(), seeded.event()).isPresent());
            await("delivery acknowledged after recovery", () -> ConsumerServiceFixture.queueDepth().drained());

            assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event()))
                    .isEqualTo(1);
            assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).hasSize(2);
            assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).hasSize(1);
            assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).hasSize(1);
            assertThat(attemptCount(seeded))
                    .as("recovery materialized instead of writing a fifth diagnostic").isEqualTo(
                            properties.maxAttempts() - 1);
        } finally {
            ConsumerServiceFixture.grantInsert("email_job");
            ConsumerServiceFixture.clearDiagnosticWriteFault();
        }
    }

    @Test
    void midBudgetWriteFailuresLeaveTheDurableCounterBelowTheLocalBudgetAndQuarantineOnRecovery() {
        var actor = ConsumerServiceFixture.createActor("terminal-quarantine");
        var member = ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true);
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted",
                List.of(RecipientSeed.both(member)));
        ConsumerServiceFixture.revokeInsert("email_job");
        ConsumerServiceFixture.failDiagnosticWritesAtAttempt(3);
        try {
            publish(seeded);
            await("two committed attempts", () -> attemptCount(seeded) == 2);
            // The local budget reaches its cap repeatedly while the durable counter stays at 2 (< 5):
            // the delivery must remain unacknowledged and must not produce effects.
            assertDeliveryHeldWithoutEffects(seeded);

            // Recovery of the diagnostic channel only: materialization still fails, so the remaining
            // attempts commit and the terminal record acknowledges the delivery without any effect.
            ConsumerServiceFixture.clearDiagnosticWriteFault();
            await("durable attempts reach the cap", () -> attemptCount(seeded) == properties.maxAttempts());
            await("quarantined delivery acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

            assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event()))
                    .isZero();
            assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
            assertThat(ConsumerServiceFixture.inAppNotifications(seeded.company(), seeded.event())).isEmpty();
            assertThat(ConsumerServiceFixture.emailJobs(seeded.company(), seeded.event())).isEmpty();
            var diagnostic = ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).get(0);
            assertThat(diagnostic.errorCode()).isEqualTo("materialization_failed");
            assertThat(diagnostic.attempts()).isEqualTo(properties.maxAttempts());
        } finally {
            ConsumerServiceFixture.grantInsert("email_job");
            ConsumerServiceFixture.clearDiagnosticWriteFault();
        }
    }

    /**
     * The delivery must be neither acknowledged nor materialized: the wire must show it as
     * unacknowledged (stats can lag behind the delivery, so that state is awaited first), it must
     * stay unacknowledged through at least one full local-budget cycle, and there is no inbox/effect
     * evidence.
     */
    private void assertDeliveryHeldWithoutEffects(ConsumerServiceFixture.SeededEvent seeded) {
        await("held delivery visible as unacknowledged on the wire",
                () -> ConsumerServiceFixture.queueDepth().unacknowledged() >= 1);
        assertThat(ConsumerServiceFixture.queueDepth().drained())
                .as("a delivery without committed effects or terminal evidence must not be acknowledged")
                .isFalse();
        int attempts = attemptCount(seeded);
        assertThat(attempts).isLessThan(properties.maxAttempts());

        sleep(HOLD_WINDOW);

        assertThat(ConsumerServiceFixture.queueDepth().drained())
                .as("the held delivery must not be acknowledged while the counter is below the cap")
                .isFalse();
        assertThat(attemptCount(seeded)).as("the durable counter must not creep up").isEqualTo(attempts);
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.deliveries(seeded.company(), seeded.event())).isEmpty();
    }

    private int attemptCount(ConsumerServiceFixture.SeededEvent seeded) {
        return ConsumerServiceFixture.diagnostics(seeded.company(), seeded.event()).stream()
                .mapToInt(ConsumerServiceFixture.DiagnosticRow::attempts).sum();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while observing the held delivery", e);
        }
    }
}

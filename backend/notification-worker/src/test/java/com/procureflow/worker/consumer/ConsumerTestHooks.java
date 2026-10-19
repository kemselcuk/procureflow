package com.procureflow.worker.consumer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Test-only crash hook for the consumer instrumentation seam. The hook fires at most once per
 * arming, so a redelivered duplicate can still be acknowledged.
 */
final class ConsumerTestHooks {
    private static final Consumer<UUID> NOOP = eventId -> { };
    private static final AtomicReference<Consumer<UUID>> AFTER_COMMIT = new AtomicReference<>(NOOP);

    private ConsumerTestHooks() {
    }

    static void crashAfterNextCommit() {
        AFTER_COMMIT.set(eventId -> {
            throw new IllegalStateException("simulated crash after materialization commit");
        });
    }

    static void reset() {
        AFTER_COMMIT.set(NOOP);
    }

    static void afterMaterializationCommit(UUID eventId) {
        AFTER_COMMIT.getAndSet(NOOP).accept(eventId);
    }
}

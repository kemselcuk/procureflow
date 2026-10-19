package com.procureflow.worker.consumer;

import java.util.UUID;

/**
 * Failure-injection seam for the consumer pipeline, mirroring the relay instrumentation. The
 * production worker binds {@link #NOOP}; a test may replace the bean to simulate a crash after the
 * materialization transaction committed and before the broker acknowledgement.
 */
public interface ConsumerInstrumentation {

    /**
     * Invoked after the materialization transaction committed and before the broker ACK. A throw here
     * simulates a worker crash in that window: the delivery stays unacknowledged and the broker
     * redelivers it, while the inbox unique key keeps the committed effects exactly once.
     */
    void afterMaterializationCommit(UUID eventId);

    ConsumerInstrumentation NOOP = eventId -> {
    };
}

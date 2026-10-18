package com.procureflow.worker.relay;

import java.util.UUID;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * In-process injection points for failure tests. Production wires the no-op implementation;
 * no HTTP or other externally reachable debug surface exists.
 */
public interface RelayInstrumentation {
    /** Invoked once the client accepted the publish request; exposes the confirm handle to tests. */
    default void afterSendRequested(UUID eventId, CorrelationData correlationData) {}

    /** Invoked after a confirmed publication and before the fenced database save; may throw to simulate a crash. */
    default void beforePublishStateSave(UUID eventId) {}

    RelayInstrumentation NOOP = new RelayInstrumentation() {};
}

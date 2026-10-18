package com.procureflow.worker.support;

import com.procureflow.worker.relay.RelayInstrumentation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/** Test-only in-process seam that records publish attempts, delays sends and can simulate a relay crash. */
public final class CapturingInstrumentation implements RelayInstrumentation {
    private final Map<UUID, CorrelationData> sent = new ConcurrentHashMap<>();
    private final List<UUID> stateSaves = new CopyOnWriteArrayList<>();
    private final AtomicInteger delayedSendsRemaining = new AtomicInteger();
    private volatile Duration sendDelay = Duration.ZERO;
    private volatile RuntimeException saveFailure;

    public void failBeforeSave(String message) {
        this.saveFailure = new IllegalStateException(message);
    }

    /** Delays the next {@code occurrences} send-request callbacks to simulate slow broker I/O. */
    public void delaySendRequests(int occurrences, Duration delay) {
        this.sendDelay = delay;
        this.delayedSendsRemaining.set(occurrences);
    }

    @Override
    public void afterSendRequested(UUID eventId, CorrelationData correlationData) {
        sent.put(eventId, correlationData);
        if (delayedSendsRemaining.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
            try {
                Thread.sleep(sendDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while delaying the publish callback", e);
            }
        }
    }

    @Override
    public void beforePublishStateSave(UUID eventId) {
        stateSaves.add(eventId);
        if (saveFailure != null) {
            throw saveFailure;
        }
    }

    public CorrelationData correlationFor(UUID eventId) {
        return sent.get(eventId);
    }

    public List<UUID> stateSaves() {
        return List.copyOf(stateSaves);
    }
}

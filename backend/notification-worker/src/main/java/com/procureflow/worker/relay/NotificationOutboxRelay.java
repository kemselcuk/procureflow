package com.procureflow.worker.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Bounded outbox relay. Claims the next due event in a short transaction, publishes it outside any
 * database transaction and records the outcome through a fenced, database-clock lease. Claiming
 * immediately before the publish keeps every lease attached to work that starts now, and late
 * confirmations can never complete stale work because every state save locks the row and re-checks
 * the current unexpired fencing token against the live database clock.
 */
@Component
public class NotificationOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxRelay.class);

    private final DispatchStore store;
    private final OutboxPublisher publisher;
    private final RelayProperties properties;
    private final RelayInstrumentation instrumentation;

    public NotificationOutboxRelay(DispatchStore store, OutboxPublisher publisher, RelayProperties properties,
                                   RelayInstrumentation instrumentation) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.instrumentation = instrumentation;
    }

    /**
     * Processes at most {@code max-inflight} due events in this invocation. Each event is claimed
     * immediately before its own publish, so a lease is never taken for work that then waits behind
     * another event's broker I/O; a claim whose own I/O overran its lease is fenced, reclaimed and
     * legitimately republished by a later cycle.
     */
    public void runOnce() {
        int processed = 0;
        while (processed < properties.maxInflight()) {
            DispatchStore.ClaimResult claimed;
            try {
                claimed = store.claimDue(properties, 1);
            } catch (DataAccessException e) {
                // Database outages are retried on the next poll; the claim transaction rolled back.
                log.warn("Relay could not claim dispatch work: {}", e.getClass().getSimpleName());
                return;
            }
            if (claimed.quarantined() > 0) {
                log.warn("Quarantined {} dispatch row(s) that exhausted {} attempts",
                        claimed.quarantined(), properties.maxAttempts());
            }
            if (claimed.events().isEmpty()) {
                return;
            }
            for (ClaimedEvent event : claimed.events()) {
                publishOne(event);
            }
            processed += claimed.events().size();
        }
    }

    private void publishOne(ClaimedEvent event) {
        PublishResult result = publisher.publish(event);
        if (result.delivered()) {
            // Failure-injection seam: a throw here simulates a relay crash after the broker confirm
            // and before the state save, which legitimately duplicates the message on retry.
            instrumentation.beforePublishStateSave(event.eventId());
            if (!store.markPublished(event)) {
                log.warn("Ignored stale publish completion event={} attempt={} (lease expired or reclaimed)",
                        event.eventId(), event.attempt());
            }
            return;
        }
        long delayMillis = properties.retryDelayMillis(event.attempt());
        boolean saved = store.markFailed(event, result.failure(), delayMillis, properties.maxAttempts());
        if (!saved) {
            log.warn("Ignored stale publish failure event={} attempt={} code={} (lease expired or reclaimed)",
                    event.eventId(), event.attempt(), result.failure().code());
        } else if (event.attempt() >= properties.maxAttempts()) {
            log.warn("Quarantined event={} after {} attempts code={}",
                    event.eventId(), event.attempt(), result.failure().code());
        } else {
            log.info("Deferred event={} attempt={} code={} retryInMs={}",
                    event.eventId(), event.attempt(), result.failure().code(), delayMillis);
        }
    }
}

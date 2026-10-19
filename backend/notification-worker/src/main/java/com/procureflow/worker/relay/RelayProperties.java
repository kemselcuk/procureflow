package com.procureflow.worker.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded relay configuration. Every value is validated at startup so a malformed deployment
 * fails fast instead of hot-looping or stranding leased work.
 *
 * <p>Every duration is converted to milliseconds or fractional seconds later, so a positive
 * sub-millisecond value must be rejected: it would round to zero and expire a lease immediately,
 * turn the publisher confirm wait into an instant timeout, retry a failed publish with no delay or
 * schedule the poll with no wait. The ranges are compared on the {@link Duration} values before any
 * conversion, so an absurd duration fails as a validation error instead of an arithmetic overflow,
 * and the ceilings keep every accepted value far away from that overflow.
 */
@ConfigurationProperties("worker.relay")
public record RelayProperties(
        int batchSize,
        int maxInflight,
        Duration leaseTimeout,
        Duration confirmTimeout,
        int maxAttempts,
        Duration retryBackoff,
        double retryBackoffMultiplier,
        Duration retryBackoffMax,
        Duration pollInterval) {

    /** Lease and publisher-confirm waits share one five-minute ceiling. */
    private static final Duration TIMEOUT_CEILING = Duration.ofMinutes(5);
    /** Retry waits and the poll interval stay within one hour. */
    private static final Duration WAIT_CEILING = Duration.ofHours(1);
    private static final Duration MILLISECOND_FLOOR = Duration.ofMillis(1);
    private static final Duration POLL_INTERVAL_FLOOR = Duration.ofMillis(50);

    public RelayProperties {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("worker.relay.batch-size must be between 1 and 500");
        }
        if (maxInflight < batchSize || maxInflight > 1000) {
            throw new IllegalArgumentException(
                    "worker.relay.max-inflight must be between batch-size and 1000");
        }
        requireMillisRange(leaseTimeout, "worker.relay.lease-timeout", TIMEOUT_CEILING);
        requireMillisRange(confirmTimeout, "worker.relay.confirm-timeout", TIMEOUT_CEILING);
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("worker.relay.max-attempts must be between 1 and 100");
        }
        requireMillisRange(retryBackoff, "worker.relay.retry-backoff", WAIT_CEILING);
        if (!(retryBackoffMultiplier >= 1.0) || retryBackoffMultiplier > 10.0) {
            throw new IllegalArgumentException(
                    "worker.relay.retry-backoff-multiplier must be between 1.0 and 10.0");
        }
        requireMillisRange(retryBackoffMax, "worker.relay.retry-backoff-max", WAIT_CEILING);
        if (retryBackoffMax.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException(
                    "worker.relay.retry-backoff-max must not be shorter than worker.relay.retry-backoff");
        }
        requireMillisRange(pollInterval, "worker.relay.poll-interval", WAIT_CEILING);
        if (pollInterval.compareTo(POLL_INTERVAL_FLOOR) < 0) {
            throw new IllegalArgumentException("worker.relay.poll-interval must be at least 50ms");
        }
    }

    /** Exponential backoff for the just-completed attempt, capped at the configured maximum. */
    public long retryDelayMillis(int attempt) {
        double millis = retryBackoff.toMillis() * Math.pow(retryBackoffMultiplier, Math.max(0, attempt - 1));
        return (long) Math.min(millis, retryBackoffMax.toMillis());
    }

    /**
     * Requires a duration within {@code [1ms, ceiling]}. Every such value is converted to
     * milliseconds or fractional seconds later, so a zero-millisecond conversion is rejected and
     * the ceiling keeps the conversion far away from overflow. The range is compared on the
     * {@link Duration} values before calling {@link Duration#toMillis()}, so oversized input fails
     * as a validation error instead of an arithmetic exception.
     */
    private static void requireMillisRange(Duration value, String property, Duration ceiling) {
        if (value == null || value.compareTo(MILLISECOND_FLOOR) < 0) {
            throw new IllegalArgumentException(
                    property + " must be at least 1ms so it never converts to a zero-millisecond interval");
        }
        if (value.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException(property + " must not exceed " + ceiling);
        }
    }
}

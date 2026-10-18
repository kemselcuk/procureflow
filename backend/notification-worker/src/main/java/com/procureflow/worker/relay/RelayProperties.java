package com.procureflow.worker.relay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded relay configuration. Every value is validated at startup so a malformed deployment
 * fails fast instead of hot-looping or stranding leased work.
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

    public RelayProperties {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("worker.relay.batch-size must be between 1 and 500");
        }
        if (maxInflight < batchSize || maxInflight > 1000) {
            throw new IllegalArgumentException(
                    "worker.relay.max-inflight must be between batch-size and 1000");
        }
        requirePositive(leaseTimeout, "worker.relay.lease-timeout");
        requirePositive(confirmTimeout, "worker.relay.confirm-timeout");
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("worker.relay.max-attempts must be between 1 and 100");
        }
        requirePositive(retryBackoff, "worker.relay.retry-backoff");
        if (!(retryBackoffMultiplier >= 1.0) || retryBackoffMultiplier > 10.0) {
            throw new IllegalArgumentException(
                    "worker.relay.retry-backoff-multiplier must be between 1.0 and 10.0");
        }
        requirePositive(retryBackoffMax, "worker.relay.retry-backoff-max");
        if (retryBackoffMax.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException(
                    "worker.relay.retry-backoff-max must not be shorter than worker.relay.retry-backoff");
        }
        requirePositive(pollInterval, "worker.relay.poll-interval");
    }

    /** Exponential backoff for the just-completed attempt, capped at the configured maximum. */
    public long retryDelayMillis(int attempt) {
        double millis = retryBackoff.toMillis() * Math.pow(retryBackoffMultiplier, Math.max(0, attempt - 1));
        return (long) Math.min(millis, retryBackoffMax.toMillis());
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration");
        }
    }
}

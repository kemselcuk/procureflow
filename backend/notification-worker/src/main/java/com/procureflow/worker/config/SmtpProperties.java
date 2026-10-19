package com.procureflow.worker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded local SMTP capture configuration. Every value is validated at startup so a malformed
 * deployment fails fast instead of hot-looping, stranding leases or hanging on an unbounded socket.
 * Every duration that is later converted to milliseconds must be at least one millisecond: a
 * positive sub-millisecond value would round to zero, which turns a JavaMail socket timeout into an
 * infinite wait, expires a lease immediately or schedules a retry with no delay. The ranges are
 * checked on the {@link Duration} values before any conversion, so an absurd value fails as a
 * validation error instead of an arithmetic overflow. Only the fictional local Mailpit target is
 * supported; there is no credential property because the loopback capture endpoint is
 * unauthenticated and no external provider is configured.
 */
@ConfigurationProperties("worker.smtp")
public record SmtpProperties(
        boolean enabled,
        boolean scheduled,
        String host,
        int port,
        Duration leaseTimeout,
        Duration connectionTimeout,
        Duration readTimeout,
        Duration writeTimeout,
        int maxAttempts,
        Duration retryBackoff,
        double retryBackoffMultiplier,
        Duration retryBackoffMax,
        Duration pollInterval,
        int workLimit,
        int cleanupBatchSize) {

    /** Socket timeouts and the lease share one five-minute ceiling. */
    private static final Duration TIMEOUT_CEILING = Duration.ofMinutes(5);
    /** Retry waits and the poll interval stay within one hour. */
    private static final Duration WAIT_CEILING = Duration.ofHours(1);
    private static final Duration MILLISECOND_FLOOR = Duration.ofMillis(1);
    private static final Duration POLL_INTERVAL_FLOOR = Duration.ofMillis(50);

    public SmtpProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("worker.smtp.host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("worker.smtp.port must be between 1 and 65535");
        }
        requireMillisRange(leaseTimeout, "worker.smtp.lease-timeout", TIMEOUT_CEILING);
        requireMillisRange(connectionTimeout, "worker.smtp.connection-timeout", TIMEOUT_CEILING);
        requireMillisRange(readTimeout, "worker.smtp.read-timeout", TIMEOUT_CEILING);
        requireMillisRange(writeTimeout, "worker.smtp.write-timeout", TIMEOUT_CEILING);
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("worker.smtp.max-attempts must be between 1 and 100");
        }
        requireMillisRange(retryBackoff, "worker.smtp.retry-backoff", WAIT_CEILING);
        if (!(retryBackoffMultiplier >= 1.0) || retryBackoffMultiplier > 10.0) {
            throw new IllegalArgumentException(
                    "worker.smtp.retry-backoff-multiplier must be between 1.0 and 10.0");
        }
        requireMillisRange(retryBackoffMax, "worker.smtp.retry-backoff-max", WAIT_CEILING);
        if (retryBackoffMax.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException(
                    "worker.smtp.retry-backoff-max must not be shorter than worker.smtp.retry-backoff");
        }
        requireMillisRange(pollInterval, "worker.smtp.poll-interval", WAIT_CEILING);
        if (pollInterval.compareTo(POLL_INTERVAL_FLOOR) < 0) {
            throw new IllegalArgumentException("worker.smtp.poll-interval must be at least 50ms");
        }
        if (workLimit < 1 || workLimit > 50) {
            throw new IllegalArgumentException("worker.smtp.work-limit must be between 1 and 50");
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > 200) {
            throw new IllegalArgumentException("worker.smtp.cleanup-batch-size must be between 1 and 200");
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

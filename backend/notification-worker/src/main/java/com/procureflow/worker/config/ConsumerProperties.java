package com.procureflow.worker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounded notification-consumer configuration. Every value is validated at startup so a malformed
 * deployment fails fast instead of hot-looping, blocking its queue or retrying without a limit.
 *
 * <p>The runtime enable flag {@code worker.consumer.enabled} is intentionally not part of this
 * record: the listener reads it through its conditional annotation with a default of {@code true}
 * when the property is absent, so the worker consumes by default and no YAML edit is required.
 * Every other value below has an explicit bound, including the maximum accepted message size, which
 * is enforced before any JSON parsing.
 *
 * <p>Every duration is converted to milliseconds later, so a positive sub-millisecond value must be
 * rejected: it would round to zero and retry with no delay or stop the listener without waiting for
 * in-flight work. The ranges are compared on the {@link Duration} values before any conversion, so
 * an absurd duration fails as a validation error instead of an arithmetic overflow, and the
 * ceilings keep every accepted value far away from that overflow. The shutdown ceiling is five
 * minutes, far beyond the worker's 15-second statement timeout, so a graceful stop stays bounded
 * without cutting off a commit that is still making progress.
 */
@ConfigurationProperties("worker.consumer")
public record ConsumerProperties(
        @DefaultValue("8") int prefetchCount,
        @DefaultValue("1") int concurrentConsumers,
        @DefaultValue("2") int maxConcurrentConsumers,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT1S") Duration retryBackoff,
        @DefaultValue("2.0") double retryBackoffMultiplier,
        @DefaultValue("PT30S") Duration retryBackoffMax,
        @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue("PT20S") Duration shutdownTimeout) {

    /** The graceful-stop wait stays within one five-minute ceiling. */
    private static final Duration SHUTDOWN_CEILING = Duration.ofMinutes(5);
    /** Retry waits stay within one hour. */
    private static final Duration WAIT_CEILING = Duration.ofHours(1);
    private static final Duration MILLISECOND_FLOOR = Duration.ofMillis(1);

    public ConsumerProperties {
        if (prefetchCount < 1 || prefetchCount > 100) {
            throw new IllegalArgumentException("worker.consumer.prefetch-count must be between 1 and 100");
        }
        if (concurrentConsumers < 1 || concurrentConsumers > 8) {
            throw new IllegalArgumentException("worker.consumer.concurrent-consumers must be between 1 and 8");
        }
        if (maxConcurrentConsumers < concurrentConsumers || maxConcurrentConsumers > 8) {
            throw new IllegalArgumentException("worker.consumer.max-concurrent-consumers must be between "
                    + "concurrent-consumers and 8");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("worker.consumer.max-attempts must be between 1 and 10");
        }
        requireMillisRange(retryBackoff, "worker.consumer.retry-backoff", WAIT_CEILING);
        if (!(retryBackoffMultiplier >= 1.0) || retryBackoffMultiplier > 10.0) {
            throw new IllegalArgumentException("worker.consumer.retry-backoff-multiplier must be between 1.0 and 10.0");
        }
        requireMillisRange(retryBackoffMax, "worker.consumer.retry-backoff-max", WAIT_CEILING);
        if (retryBackoffMax.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException(
                    "worker.consumer.retry-backoff-max must not be shorter than worker.consumer.retry-backoff");
        }
        if (maxBodyBytes < 256 || maxBodyBytes > 1_048_576) {
            throw new IllegalArgumentException("worker.consumer.max-body-bytes must be between 256 and 1048576");
        }
        requireMillisRange(shutdownTimeout, "worker.consumer.shutdown-timeout", SHUTDOWN_CEILING);
    }

    /** Exponential backoff for a just-failed attempt, capped at the configured maximum. */
    public long retryDelayMillis(int attempt) {
        double millis = retryBackoff.toMillis() * Math.pow(retryBackoffMultiplier, Math.max(0, attempt - 1));
        return (long) Math.min(millis, retryBackoffMax.toMillis());
    }

    /**
     * Requires a duration within {@code [1ms, ceiling]}. Every such value is converted to
     * milliseconds later, so a zero-millisecond conversion is rejected and the ceiling keeps the
     * conversion far away from overflow. The range is compared on the {@link Duration} values
     * before calling {@link Duration#toMillis()}, so oversized input fails as a validation error
     * instead of an arithmetic exception.
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

package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procureflow.worker.config.ConsumerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Bounded configuration contract: documented defaults and fail-fast validation. */
class ConsumerPropertiesTest {

    @Test
    void defaultsAreBoundedAndProduceExponentialBackoff() {
        ConsumerProperties properties = defaults();

        assertThat(properties.prefetchCount()).isEqualTo(8);
        assertThat(properties.concurrentConsumers()).isEqualTo(1);
        assertThat(properties.maxConcurrentConsumers()).isEqualTo(2);
        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(properties.maxBodyBytes()).isEqualTo(65_536);
        assertThat(properties.retryDelayMillis(1)).isEqualTo(1_000);
        assertThat(properties.retryDelayMillis(2)).isEqualTo(2_000);
        assertThat(properties.retryDelayMillis(3)).isEqualTo(4_000);
        assertThat(properties.retryDelayMillis(10)).isEqualTo(30_000);
    }

    @Test
    void rejectsUnboundedOrContradictoryConfiguration() {
        assertThatThrownBy(() -> properties(0, 1, 2, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("prefetch-count");
        assertThatThrownBy(() -> properties(8, 0, 2, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("concurrent-consumers");
        assertThatThrownBy(() -> properties(8, 4, 3, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("max-concurrent-consumers");
        assertThatThrownBy(() -> properties(8, 1, 2, 0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> properties(8, 1, 2, 11, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ZERO, 2.0, Duration.ofSeconds(30),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(30),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("multiplier");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(1),
                65_536, Duration.ofSeconds(20))).hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                128, Duration.ofSeconds(20))).hasMessageContaining("max-body-bytes");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, Duration.ZERO)).hasMessageContaining("shutdown-timeout");
    }

    @Test
    void subMillisecondDurationsAreRejectedBeforeTheyConvertToZeroMillis() {
        // Each duration below used to pass validation while its millisecond conversion was zero: a
        // retry with no delay at all, or a container shutdown that cannot wait for in-flight work.
        assertThat(Duration.ofNanos(1).toMillis()).as("the raw conversion a sub-millisecond value produces")
                .isZero();

        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofNanos(1), 2.0, Duration.ofNanos(1),
                65_536, Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofMillis(1), 2.0, Duration.ofNanos(1),
                65_536, Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shutdown-timeout");
    }

    @Test
    void oversizedDurationsFailValidationInsteadOfArithmeticOverflow() {
        Duration overflow = Duration.ofSeconds(Long.MAX_VALUE);
        assertThatThrownBy(overflow::toMillis).as("the raw conversion that an absurd value would reach")
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> properties(8, 1, 2, 5, overflow, 2.0, overflow, 65_536,
                Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofSeconds(1), 2.0, overflow, 65_536,
                Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> properties(8, 1, 2, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                65_536, overflow))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shutdown-timeout");
    }

    @Test
    void floorValuesStayAcceptedAndNeverProduceZeroConversions() {
        var properties = properties(8, 1, 2, 5, Duration.ofMillis(1), 2.0, Duration.ofMillis(1), 65_536,
                Duration.ofMillis(1));

        assertThat(properties.retryDelayMillis(1)).as("the minimum backoff still waits a whole millisecond")
                .isEqualTo(1);
        assertThat(properties.retryDelayMillis(10)).as("the capped minimum backoff stays non-zero").isEqualTo(1);
        assertThat(properties.shutdownTimeout().toMillis()).isEqualTo(1);
    }

    private static ConsumerProperties defaults() {
        return properties(8, 1, 2, 5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 65_536,
                Duration.ofSeconds(20));
    }

    private static ConsumerProperties properties(int prefetch, int concurrency, int maxConcurrency, int maxAttempts,
                                                 Duration backoff, double multiplier, Duration maxBackoff,
                                                 int maxBodyBytes, Duration shutdownTimeout) {
        return new ConsumerProperties(prefetch, concurrency, maxConcurrency, maxAttempts, backoff, multiplier,
                maxBackoff, maxBodyBytes, shutdownTimeout);
    }
}

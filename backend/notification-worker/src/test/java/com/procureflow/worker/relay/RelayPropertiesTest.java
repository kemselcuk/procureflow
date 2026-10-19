package com.procureflow.worker.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Startup validation and bounded exponential backoff of the relay settings. */
class RelayPropertiesTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration CONFIRM = Duration.ofSeconds(2);
    private static final Duration BACKOFF = Duration.ofMillis(500);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(2);

    @Test
    void retryBackoffGrowsExponentiallyAndIsCapped() {
        var properties = new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1));
        assertThat(properties.retryDelayMillis(1)).isEqualTo(500);
        assertThat(properties.retryDelayMillis(2)).isEqualTo(1_000);
        assertThat(properties.retryDelayMillis(3)).isEqualTo(2_000);
        assertThat(properties.retryDelayMillis(25)).as("backoff never exceeds the configured maximum")
                .isEqualTo(2_000);
    }

    @Test
    void invalidValuesFailFastAtStartup() {
        assertThatThrownBy(() -> new RelayProperties(0, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1))).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new RelayProperties(10, 5, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1))).hasMessageContaining("max-inflight");
        assertThatThrownBy(() -> new RelayProperties(10, 40, Duration.ZERO, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1))).hasMessageContaining("lease-timeout");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, null, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1))).hasMessageContaining("confirm-timeout");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 0, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1))).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 0.5, BACKOFF_MAX,
                Duration.ofSeconds(1))).hasMessageContaining("retry-backoff-multiplier");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF_MAX, 2.0, BACKOFF,
                Duration.ofSeconds(1))).hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(-1))).hasMessageContaining("poll-interval");
    }

    @Test
    void subMillisecondDurationsAreRejectedBeforeTheyConvertToZeroMillis() {
        // Each duration below used to pass validation while its millisecond conversion was zero: an
        // immediately expiring lease, a confirm wait of zero, or a retry/poll with no delay at all.
        assertThat(Duration.ofNanos(1).toMillis()).as("the raw conversion a sub-millisecond value produces")
                .isZero();

        assertThatThrownBy(() -> new RelayProperties(10, 40, Duration.ofNanos(1), CONFIRM, 5, BACKOFF, 2.0,
                BACKOFF_MAX, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease-timeout");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, Duration.ofNanos(1), 5, BACKOFF, 2.0,
                BACKOFF_MAX, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirm-timeout");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, Duration.ofNanos(1), 2.0,
                Duration.ofNanos(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofMillis(49)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("poll-interval");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("poll-interval");
    }

    @Test
    void subMillisecondRetryCapIsNeverAcceptedAlongsideAValidBackoff() {
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, Duration.ofMillis(1), 2.0,
                Duration.ofNanos(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff-max");
    }

    @Test
    void oversizedDurationsFailValidationInsteadOfArithmeticOverflow() {
        Duration overflow = Duration.ofSeconds(Long.MAX_VALUE);
        assertThatThrownBy(overflow::toMillis).as("the raw conversion that an absurd value would reach")
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> new RelayProperties(10, 40, overflow, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease-timeout");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, overflow, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirm-timeout");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, overflow, 2.0, overflow,
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, overflow,
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                overflow))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("poll-interval");
    }

    @Test
    void floorValuesStayAcceptedAndNeverProduceZeroConversions() {
        var properties = new RelayProperties(10, 40, Duration.ofMillis(1), Duration.ofMillis(1), 5,
                Duration.ofMillis(1), 2.0, Duration.ofMillis(1), Duration.ofMillis(50));

        assertThat(properties.leaseTimeout().toMillis()).isEqualTo(1);
        assertThat(properties.confirmTimeout().toMillis()).isEqualTo(1);
        assertThat(properties.retryDelayMillis(1)).as("the minimum backoff still waits a whole millisecond")
                .isEqualTo(1);
        assertThat(properties.retryDelayMillis(25)).as("the capped minimum backoff stays non-zero").isEqualTo(1);
        assertThat(properties.pollInterval().toMillis()).isEqualTo(50);
    }

    @Test
    void hourlyPollIntervalRemainsAccepted() {
        var properties = new RelayProperties(10, 40, LEASE, CONFIRM, 5, BACKOFF, 2.0, BACKOFF_MAX,
                Duration.ofHours(1));

        assertThat(properties.pollInterval().toMillis()).isEqualTo(3_600_000);
    }
}

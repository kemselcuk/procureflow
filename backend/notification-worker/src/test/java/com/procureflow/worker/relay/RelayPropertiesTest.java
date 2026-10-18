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
}

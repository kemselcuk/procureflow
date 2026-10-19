package com.procureflow.worker.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procureflow.worker.config.SmtpProperties;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Startup validation and bounded exponential backoff of the SMTP runner settings. */
class SmtpPropertiesTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration BACKOFF = Duration.ofMillis(500);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(2);
    private static final Duration POLL = Duration.ofSeconds(1);

    @Test
    void retryBackoffGrowsExponentiallyAndIsCapped() {
        var properties = new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT, TIMEOUT,
                5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20);
        assertThat(properties.retryDelayMillis(1)).isEqualTo(500);
        assertThat(properties.retryDelayMillis(2)).isEqualTo(1_000);
        assertThat(properties.retryDelayMillis(3)).isEqualTo(2_000);
        assertThat(properties.retryDelayMillis(25)).as("backoff never exceeds the configured maximum")
                .isEqualTo(2_000);
    }

    @Test
    void invalidValuesFailFastAtStartup() {
        assertThatThrownBy(() -> new SmtpProperties(true, true, "  ", 1025, LEASE, TIMEOUT, TIMEOUT, TIMEOUT,
                5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20)).hasMessageContaining("host");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 0, LEASE, TIMEOUT, TIMEOUT, TIMEOUT,
                5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20)).hasMessageContaining("port");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, Duration.ZERO,
                TIMEOUT, TIMEOUT, TIMEOUT, 5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20))
                .hasMessageContaining("lease-timeout");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, null,
                TIMEOUT, TIMEOUT, 5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20))
                .hasMessageContaining("connection-timeout");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT,
                Duration.ofMinutes(6), TIMEOUT, 5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20))
                .hasMessageContaining("read-timeout");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT,
                Duration.ofSeconds(-1), 5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20))
                .hasMessageContaining("write-timeout");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT,
                TIMEOUT, 0, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20))
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT,
                TIMEOUT, 5, BACKOFF, 0.5, BACKOFF_MAX, Duration.ofSeconds(1), 10, 20))
                .hasMessageContaining("retry-backoff-multiplier");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT,
                TIMEOUT, 5, BACKOFF_MAX, 2.0, BACKOFF, Duration.ofSeconds(1), 10, 20))
                .hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT,
                TIMEOUT, 5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofMillis(10), 10, 20))
                .hasMessageContaining("poll-interval");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT,
                TIMEOUT, 5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 51, 20))
                .hasMessageContaining("work-limit");
        assertThatThrownBy(() -> new SmtpProperties(true, true, "localhost", 1025, LEASE, TIMEOUT, TIMEOUT,
                TIMEOUT, 5, BACKOFF, 2.0, BACKOFF_MAX, Duration.ofSeconds(1), 10, 0))
                .hasMessageContaining("cleanup-batch-size");
    }

    @Test
    void messageIdIsStablePerJob() {
        var jobId = java.util.UUID.randomUUID();
        assertThat(SmtpEmailSender.messageId(jobId)).isEqualTo("<procureflow-job-" + jobId + "@demo.invalid>");
        assertThat(SmtpEmailSender.messageId(jobId)).isEqualTo(SmtpEmailSender.messageId(jobId));
    }

    /**
     * Every duration that is converted to milliseconds later must be at least 1ms: a positive
     * sub-millisecond value rounds to zero, which would make a JavaMail socket timeout infinite,
     * expire a lease immediately or schedule a retry with no delay. The three socket timeouts, the
     * lease and both retry waits are covered.
     */
    @Test
    void subMillisecondDurationsAreRejectedForEveryConvertedValue() {
        assertThatThrownBy(() -> props(Duration.ofNanos(1), TIMEOUT, TIMEOUT, TIMEOUT, BACKOFF, BACKOFF_MAX, POLL))
                .as("a sub-millisecond lease would expire before its own claim commits")
                .hasMessageContaining("lease-timeout");
        assertThatThrownBy(() -> props(LEASE, Duration.ofNanos(1), TIMEOUT, TIMEOUT, BACKOFF, BACKOFF_MAX, POLL))
                .as("a sub-millisecond connect timeout maps to JavaMail 0 (infinite)")
                .hasMessageContaining("connection-timeout");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, Duration.ofNanos(1), TIMEOUT, BACKOFF, BACKOFF_MAX, POLL))
                .as("a sub-millisecond read timeout maps to JavaMail 0 (infinite)")
                .hasMessageContaining("read-timeout");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, Duration.ofNanos(1), BACKOFF, BACKOFF_MAX, POLL))
                .as("a sub-millisecond write timeout maps to JavaMail 0 (infinite)")
                .hasMessageContaining("write-timeout");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, TIMEOUT, Duration.ofNanos(1), BACKOFF_MAX, POLL))
                .as("a sub-millisecond backoff would retry immediately")
                .hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, TIMEOUT, BACKOFF, Duration.ofNanos(1), POLL))
                .hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, TIMEOUT, BACKOFF, BACKOFF_MAX, Duration.ofNanos(1)))
                .hasMessageContaining("poll-interval");
    }

    /**
     * Whatever the validation accepts must map to actual finite JavaMail socket settings: the
     * regression asserts the real properties handed to JavaMail, not the error string, so a
     * silently rounded-to-zero value is caught even if validation were to accept it.
     */
    @Test
    void acceptedSocketDurationsNeverMapToAnInfiniteJavaMailTimeout() {
        int accepted = 0;
        for (Duration candidate : List.of(Duration.ofNanos(1), Duration.ofNanos(999_999),
                Duration.ofMillis(1), TIMEOUT)) {
            SmtpProperties properties;
            try {
                properties = props(LEASE, candidate, candidate, candidate, BACKOFF, BACKOFF_MAX, POLL);
            } catch (IllegalArgumentException rejected) {
                continue;
            }
            accepted++;
            Properties mail = new SmtpEmailSender(properties).javaMailProperties();
            assertThat(Long.parseLong(mail.getProperty("mail.smtp.connectiontimeout")))
                    .as("actual connect timeout for %s must never be zero", candidate).isGreaterThanOrEqualTo(1);
            assertThat(Long.parseLong(mail.getProperty("mail.smtp.timeout")))
                    .as("actual read timeout for %s must never be zero", candidate).isGreaterThanOrEqualTo(1);
            assertThat(Long.parseLong(mail.getProperty("mail.smtp.writetimeout")))
                    .as("actual write timeout for %s must never be zero", candidate).isGreaterThanOrEqualTo(1);
        }
        assertThat(accepted).as("the 1ms and 2s boundary values stay valid configurations")
                .isGreaterThanOrEqualTo(2);
    }

    /** Finite ceilings keep every millisecond conversion far away from overflow. */
    @Test
    void durationsAboveTheirFiniteCeilingsAreRejected() {
        assertThatThrownBy(() -> props(Duration.ofMinutes(6), TIMEOUT, TIMEOUT, TIMEOUT, BACKOFF, BACKOFF_MAX, POLL))
                .hasMessageContaining("lease-timeout");
        assertThatThrownBy(() -> props(LEASE, Duration.ofMinutes(6), TIMEOUT, TIMEOUT, BACKOFF, BACKOFF_MAX, POLL))
                .hasMessageContaining("connection-timeout");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, Duration.ofMinutes(6), BACKOFF, BACKOFF_MAX, POLL))
                .hasMessageContaining("write-timeout");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, TIMEOUT, Duration.ofHours(2), BACKOFF_MAX, POLL))
                .hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, TIMEOUT, BACKOFF, Duration.ofHours(2), POLL))
                .hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> props(LEASE, TIMEOUT, TIMEOUT, TIMEOUT, BACKOFF, BACKOFF_MAX, Duration.ofHours(2)))
                .hasMessageContaining("poll-interval");
    }

    @Test
    void actualJavaMailSocketSettingsMatchTheConfiguredMilliseconds() {
        Properties mail = new SmtpEmailSender(props(LEASE, Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(4), BACKOFF, BACKOFF_MAX, POLL)).javaMailProperties();
        assertThat(mail.getProperty("mail.smtp.connectiontimeout")).isEqualTo("2000");
        assertThat(mail.getProperty("mail.smtp.timeout")).isEqualTo("3000");
        assertThat(mail.getProperty("mail.smtp.writetimeout")).isEqualTo("4000");
        assertThat(mail.getProperty("mail.smtp.auth")).isEqualTo("false");
        assertThat(mail.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
    }

    /** The minimum accepted lease/backoff boundaries still produce non-zero waits. */
    @Test
    void minimumAcceptedDurationsStillProduceNonZeroWaits() {
        var properties = props(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1),
                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(50));
        assertThat(properties.retryDelayMillis(1)).as("the first retry wait is exactly one millisecond")
                .isEqualTo(1);
        assertThat(properties.retryDelayMillis(25)).as("the cap keeps the retry wait non-zero").isEqualTo(1);
    }

    /** Canonical valid properties with three explicit socket and wait overrides. */
    private static SmtpProperties props(Duration lease, Duration connection, Duration read, Duration write,
                                        Duration backoff, Duration backoffMax, Duration poll) {
        return new SmtpProperties(true, true, "localhost", 1025, lease, connection, read, write, 5, backoff, 2.0,
                backoffMax, poll, 10, 20);
    }
}

package com.procureflow.worker.support;

import com.procureflow.worker.config.SmtpProperties;
import com.procureflow.worker.email.EmailInstrumentation;
import com.procureflow.worker.email.EmailJobRunner;
import com.procureflow.worker.email.EmailJobStore;
import com.procureflow.worker.email.SmtpEmailSender;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boot context wired exactly like production (worker JDBC login, worker-owned SMTP settings), but
 * pointed at the real service fixtures and with every scheduled runner disabled: this base is used
 * only by SMTP email-job tests, which drive {@code runOnce()} deterministically and never exercise
 * the relay or a consumer. The relay scheduler is disabled and no broker is contacted; the future
 * consumer flags are set defensively so an integrated consumer cannot consume here.
 */
@SpringBootTest
public abstract class EmailIntegrationTestBase {
    @Autowired
    protected EmailJobStore emailJobStore;
    @Autowired
    protected EmailJobRunner emailRunner;
    @Autowired
    protected SmtpEmailSender smtpSender;
    @Autowired
    protected SmtpProperties properties;

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmailServiceFixture::jdbcUrl);
        registry.add("spring.datasource.username", () -> EmailServiceFixture.WORKER_USER);
        registry.add("spring.datasource.password", EmailServiceFixture::workerPassword);
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(EmailServiceFixture.closedLoopbackPort()));
        registry.add("spring.rabbitmq.connection-timeout", () -> "PT0.2S");
        registry.add("worker.relay.scheduled", () -> "false");
        registry.add("worker.consumer.enabled", () -> "false");
        registry.add("worker.consumer.scheduled", () -> "false");
        registry.add("worker.smtp.enabled", () -> "true");
        registry.add("worker.smtp.scheduled", () -> "false");
        registry.add("worker.smtp.host", EmailServiceFixture::mailpitHost);
        registry.add("worker.smtp.port", () -> String.valueOf(EmailServiceFixture.mailpitPort()));
        registry.add("worker.smtp.lease-timeout", () -> "PT30S");
        registry.add("worker.smtp.connection-timeout", () -> "PT2S");
        registry.add("worker.smtp.read-timeout", () -> "PT2S");
        registry.add("worker.smtp.write-timeout", () -> "PT2S");
        registry.add("worker.smtp.max-attempts", () -> "5");
        registry.add("worker.smtp.retry-backoff", () -> "PT0.5S");
        registry.add("worker.smtp.retry-backoff-multiplier", () -> "2.0");
        registry.add("worker.smtp.retry-backoff-max", () -> "PT2S");
        registry.add("worker.smtp.poll-interval", () -> "PT1H");
        registry.add("worker.smtp.work-limit", () -> "10");
        registry.add("worker.smtp.cleanup-batch-size", () -> "20");
    }

    @BeforeEach
    void resetSharedState() {
        EmailServiceFixture.reset();
        emailJobStore.restartRotation();
    }

    /** Production runner with a test-only failure-injection seam and an explicit sender. */
    protected EmailJobRunner runnerWith(SmtpEmailSender sender, EmailInstrumentation instrumentation) {
        return new EmailJobRunner(emailJobStore, sender, properties, instrumentation);
    }

    /** SMTP settings for an arbitrary local endpoint, keeping the bounded runner settings. */
    protected SmtpProperties smtpTo(String host, int port) {
        return new SmtpProperties(true, false, host, port, Duration.ofSeconds(30), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(2), properties.maxAttempts(), properties.retryBackoff(),
                properties.retryBackoffMultiplier(), properties.retryBackoffMax(), Duration.ofHours(1),
                properties.workLimit(), properties.cleanupBatchSize());
    }

    /** SMTP settings with a short lease so expiry is observable inside a running test. */
    protected SmtpProperties shortLeaseProperties() {
        return new SmtpProperties(true, false, properties.host(), properties.port(), Duration.ofSeconds(3),
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), properties.maxAttempts(),
                properties.retryBackoff(), properties.retryBackoffMultiplier(), properties.retryBackoffMax(),
                Duration.ofHours(1), properties.workLimit(), properties.cleanupBatchSize());
    }
}

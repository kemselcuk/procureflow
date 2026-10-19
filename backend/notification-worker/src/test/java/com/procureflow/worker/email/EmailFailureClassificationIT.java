package com.procureflow.worker.email;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.procureflow.worker.config.SmtpProperties;
import com.procureflow.worker.support.EmailIntegrationTestBase;
import com.procureflow.worker.support.EmailServiceFixture;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Real SMTP reply-classification evidence. The fault cases use an isolated local SMTP server on
 * loopback (a real TCP conversation), explicitly NOT Mailpit; only the successful-recovery path
 * uses the pinned Mailpit capture.
 */
class EmailFailureClassificationIT extends EmailIntegrationTestBase {

    @Test
    void permanentReplyQuarantinesImmediatelyAndNeverLogsServerText() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        Logger logger = (Logger) LoggerFactory.getLogger("com.procureflow.worker.email");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (FaultySmtpServer server = new FaultySmtpServer(FaultySmtpServer.Behavior.REJECT_PERMANENT)) {
            var sender = new SmtpEmailSender(smtpTo("127.0.0.1", server.port()));
            runnerWith(sender, new CapturingEmailInstrumentation()).runOnce();
        } finally {
            logger.detachAppender(appender);
        }

        var row = EmailServiceFixture.job(seeded.job());
        assertThat(row.state()).isEqualTo("QUARANTINED");
        assertThat(row.errorCode()).isEqualTo("smtp_rejected");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.leaseToken()).isNull();
        assertThat(EmailServiceFixture.mailpitTotal()).as("the fault server is not Mailpit").isZero();

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(message -> assertThat(message).contains("smtp_rejected"));
        assertThat(String.join("\n", messages))
                .as("the raw SMTP reply must never reach the logs").doesNotContain("ServerReplyMarkerCapturedOnlyByTheTest");
    }

    @Test
    void transientReplyBacksOffAndTheRealMailpitRecoversTheJob() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        try (FaultySmtpServer server = new FaultySmtpServer(FaultySmtpServer.Behavior.REJECT_TRANSIENT)) {
            var sender = new SmtpEmailSender(smtpTo("127.0.0.1", server.port()));
            runnerWith(sender, new CapturingEmailInstrumentation()).runOnce();
        }

        var failed = EmailServiceFixture.job(seeded.job());
        assertThat(failed.state()).isEqualTo("PENDING");
        assertThat(failed.errorCode()).isEqualTo("smtp_transient");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.nextAttemptAt()).isAfter(java.sql.Timestamp.from(java.time.Instant.now()));
        assertThat(emailJobStore.claimOne(properties).claimed()).isNull();

        EmailServiceFixture.makeDue(seeded.job());
        emailRunner.runOnce();
        assertThat(EmailServiceFixture.job(seeded.job()).state()).isEqualTo("SENT");
        assertThat(EmailServiceFixture.job(seeded.job()).attempts()).isEqualTo(2);
        assertThat(EmailServiceFixture.mailpitTotal()).isEqualTo(1);
    }

    @Test
    void silentServerIsCutOffByTheBoundedReadTimeoutAndClassifiedRetryable() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        try (FaultySmtpServer server = new FaultySmtpServer(FaultySmtpServer.Behavior.SILENT)) {
            SmtpProperties tight = new SmtpProperties(true, false, "127.0.0.1", server.port(),
                    Duration.ofSeconds(30), Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300),
                    properties.maxAttempts(), properties.retryBackoff(), properties.retryBackoffMultiplier(),
                    properties.retryBackoffMax(), Duration.ofHours(1), properties.workLimit(),
                    properties.cleanupBatchSize());
            long startedAt = System.nanoTime();
            runnerWith(new SmtpEmailSender(tight), new CapturingEmailInstrumentation()).runOnce();
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertThat(elapsedMillis).as("the configured read timeout must bound the send attempt")
                    .isLessThan(10_000);
        }

        var row = EmailServiceFixture.job(seeded.job());
        assertThat(row.state()).isEqualTo("PENDING");
        assertThat(row.errorCode()).isEqualTo("smtp_timeout");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(EmailServiceFixture.mailpitTotal()).isZero();
    }

    @Test
    void aNonGeneratedDestinationIsRefusedWithoutSmtpIo() {
        var sender = new SmtpEmailSender(properties);
        var job = new ClaimedEmailJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "attacker@example.invalid", 1, UUID.randomUUID(), "request.submitted",
                UUID.randomUUID(), UUID.randomUUID());

        var result = sender.send(job);

        assertThat(result.delivered()).isFalse();
        assertThat(result.failure()).isEqualTo(EmailFailure.RECIPIENT_INVALID);
        assertThat(EmailServiceFixture.mailpitTotal()).isZero();
    }
}

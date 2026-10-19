package com.procureflow.worker.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.procureflow.worker.support.EmailIntegrationTestBase;
import com.procureflow.worker.support.EmailServiceFixture;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * End-to-end email-job delivery against real Mailpit 1.31.6 with the pinned PostgreSQL fixture:
 * generated fictional destinations, stable Message-ID/headers, the acceptance-before-save duplicate
 * window, bounded outage recovery and evidence that failures never touch purchase or read state.
 */
class EmailDeliveryIT extends EmailIntegrationTestBase {

    @Test
    void validJobIsDeliveredToTheGeneratedDestinationWithStableMetadataAndSentTimestamp() {
        var seeded = EmailServiceFixture.seedJob(true);

        emailRunner.runOnce();

        var row = EmailServiceFixture.job(seeded.job());
        assertThat(row.state()).isEqualTo("SENT");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.sentAt()).isNotNull();
        assertThat(row.errorCode()).isNull();
        assertThat(row.leaseToken()).isNull();
        assertThat(row.leaseUntil()).isNull();

        var messages = EmailServiceFixture.mailpitMessages();
        assertThat(messages).hasSize(1);
        var summary = messages.get(0);
        // Mailpit's JSON strips the angle brackets; the wire header is asserted via the raw message.
        assertThat("<" + summary.path("MessageID").asText() + ">").isEqualTo(SmtpEmailSender.messageId(seeded.job()));
        assertThat(EmailServiceFixture.mailpitRaw(summary.path("ID").asText()))
                .contains("Message-ID: " + SmtpEmailSender.messageId(seeded.job()));
        assertThat(summary.path("From").path("Address").asText()).isEqualTo("notifications@demo.invalid");
        assertThat(summary.path("To").get(0).path("Address").asText()).isEqualTo(seeded.recipientAddress());
        assertThat(summary.path("Subject").asText()).isEqualTo("ProcureFlow notification: request.submitted");

        var headers = EmailServiceFixture.mailpitHeaders(summary.path("ID").asText());
        assertThat(EmailServiceFixture.header(headers, "X-ProcureFlow-Job-Id")).isEqualTo(seeded.job().toString());
        assertThat(EmailServiceFixture.header(headers, "X-ProcureFlow-Event-Id")).isEqualTo(seeded.event().toString());
        assertThat(EmailServiceFixture.header(headers, "X-ProcureFlow-Correlation-Id"))
                .isEqualTo(seeded.correlation().toString());
        assertThat(EmailServiceFixture.header(headers, "X-ProcureFlow-Schema-Version")).isEqualTo("1");

        String raw = EmailServiceFixture.mailpitRaw(summary.path("ID").asText());
        assertThat(raw).contains("Request ID: " + seeded.request());
        assertThat(raw).contains("Correlation ID: " + seeded.correlation());
        assertThat(raw).as("purchase free text, reason and amount must never be sent")
                .doesNotContain(EmailServiceFixture.TITLE_MARKER)
                .doesNotContain(EmailServiceFixture.JUSTIFICATION_MARKER)
                .doesNotContain("9876.54");
    }

    @Test
    void claimCommitsBeforeSendAndNoRowLockIsHeldDuringIoS() throws Exception {
        var seeded = EmailServiceFixture.seedJob(true);
        var capturing = new CapturingEmailInstrumentation();
        AtomicReference<String> observedState = new AtomicReference<>();
        capturing.onBeforeSend(job -> {
            try (Connection observer = EmailServiceFixture.workerConnection()) {
                try (var statement = observer.prepareStatement(
                        "SELECT state FROM email_job WHERE id = ? FOR UPDATE NOWAIT")) {
                    statement.setObject(1, job.jobId());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new IllegalStateException("the claimed job row must be visible");
                        }
                        observedState.set(rows.getString("state"));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("the job row must be lockable during SMTP I/O", e);
            }
        });

        runnerWith(smtpSender, capturing).runOnce();

        assertThat(capturing.sends()).containsExactly(seeded.job());
        assertThat(observedState.get()).as("the committed claim must be visible before the send").isEqualTo("LEASED");
        assertThat(EmailServiceFixture.job(seeded.job()).state()).isEqualTo("SENT");
    }

    @Test
    void acceptanceBeforeSaveCrashDuplicatesOnlyTheStableMessageIdOnReclaim() {
        var seeded = EmailServiceFixture.seedJob(true);
        var crash = new CapturingEmailInstrumentation();
        crash.failBeforeSave("simulated crash after SMTP acceptance and before the SENT save");

        assertThatThrownBy(() -> runnerWith(smtpSender, crash).runOnce())
                .hasMessageContaining("simulated crash after SMTP acceptance");
        var crashed = EmailServiceFixture.job(seeded.job());
        assertThat(crashed.state()).as("a crash leaves the lease behind for expiry/reclaim").isEqualTo("LEASED");
        assertThat(crashed.attempts()).isEqualTo(1);
        assertThat(crashed.sentAt()).isNull();
        assertThat(EmailServiceFixture.mailpitTotal()).as("the message was accepted before the crash").isEqualTo(1);

        EmailServiceFixture.expireLease(seeded.job());
        emailRunner.runOnce();

        var recovered = EmailServiceFixture.job(seeded.job());
        assertThat(recovered.state()).isEqualTo("SENT");
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(recovered.sentAt()).isNotNull();
        var messages = EmailServiceFixture.mailpitMessages();
        assertThat(messages).as("the retry legitimately duplicates the external message").hasSize(2);
        String stableId = SmtpEmailSender.messageId(seeded.job());
        assertThat(messages.stream().map(message -> "<" + message.path("MessageID").asText() + ">").toList())
                .containsExactly(stableId, stableId);
        assertThat(EmailServiceFixture.header(
                EmailServiceFixture.mailpitHeaders(messages.get(0).path("ID").asText()), "X-ProcureFlow-Job-Id"))
                .isEqualTo(seeded.job().toString());
        assertThat(EmailServiceFixture.purchase(seeded.request()).title()).isEqualTo(EmailServiceFixture.TITLE_MARKER);
    }

    @Test
    void smtpOutageBacksOffThenRecoversWithinTheFixtureBound() {
        var seeded = EmailServiceFixture.seedJob(true);
        String unavailableHost = "127.0.0.1";
        int unavailablePort = EmailServiceFixture.closedLoopbackPort();
        var unavailable = new SmtpEmailSender(smtpTo(unavailableHost, unavailablePort));
        var capturing = new CapturingEmailInstrumentation();

        runnerWith(unavailable, capturing).runOnce();

        var failed = EmailServiceFixture.job(seeded.job());
        assertThat(failed.state()).isEqualTo("PENDING");
        assertThat(failed.errorCode()).isEqualTo("smtp_unreachable");
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.nextAttemptAt()).isAfter(Timestamp.from(Instant.now()));
        assertThat(emailJobStore.claimOne(properties).claimed())
                .as("the failure must back off instead of hot-looping").isNull();

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        boolean sent = false;
        while (!sent && System.nanoTime() < deadline) {
            emailRunner.runOnce();
            sent = "SENT".equals(EmailServiceFixture.job(seeded.job()).state());
            if (!sent) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for SMTP recovery", e);
                }
            }
        }
        assertThat(sent).as("recovery must complete within the 60-second fixture bound").isTrue();
        assertThat(EmailServiceFixture.job(seeded.job()).attempts())
                .as("recovery republishes the backed-off job").isGreaterThanOrEqualTo(2);
        assertThat(EmailServiceFixture.mailpitTotal()).isEqualTo(1);
        assertThat("<" + EmailServiceFixture.mailpitMessages().get(0).path("MessageID").asText() + ">")
                .isEqualTo(SmtpEmailSender.messageId(seeded.job()));
    }

    @Test
    void failureInjectionLeavesPurchasesAndNotificationReadStateUntouched() {
        var seeded = EmailServiceFixture.seedJob(true);
        var notification = EmailServiceFixture.seedInAppNotification(seeded);
        var purchaseBefore = EmailServiceFixture.purchase(seeded.request());
        long auditBefore = EmailServiceFixture.auditCount(seeded.company());
        long chargesBefore = EmailServiceFixture.budgetChargeCount(seeded.company());

        var unavailable = new SmtpEmailSender(smtpTo("127.0.0.1", EmailServiceFixture.closedLoopbackPort()));
        runnerWith(unavailable, new CapturingEmailInstrumentation()).runOnce();

        assertThat(EmailServiceFixture.job(seeded.job()).state()).isEqualTo("PENDING");
        assertThat(EmailServiceFixture.purchase(seeded.request())).isEqualTo(purchaseBefore);
        assertThat(EmailServiceFixture.inAppReadAt(notification)).isNull();
        assertThat(EmailServiceFixture.delivery(seeded.delivery()).outcome()).isEqualTo("MATERIALIZED");
        assertThat(EmailServiceFixture.auditCount(seeded.company())).isEqualTo(auditBefore);
        assertThat(EmailServiceFixture.budgetChargeCount(seeded.company())).isEqualTo(chargesBefore);
        assertThat(EmailServiceFixture.mailpitTotal()).isZero();
    }

    @Test
    void runnerLogsContainOnlySanitizedCodesAndNoContent() {
        var seeded = EmailServiceFixture.seedJob(true);
        Logger logger = (Logger) LoggerFactory.getLogger("com.procureflow.worker.email");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var unavailable = new SmtpEmailSender(smtpTo("127.0.0.1", EmailServiceFixture.closedLoopbackPort()));
            runnerWith(unavailable, new CapturingEmailInstrumentation()).runOnce();
        } finally {
            logger.detachAppender(appender);
        }
        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(message -> assertThat(message).contains("smtp_unreachable"));
        assertThat(String.join("\n", messages))
                .as("no raw content, purchase text, credential or server text may be logged")
                .doesNotContain(EmailServiceFixture.TITLE_MARKER)
                .doesNotContain(EmailServiceFixture.JUSTIFICATION_MARKER)
                .doesNotContain("9876.54")
                .doesNotContain("Connection refused")
                .doesNotContain(seeded.recipientAddress());
    }
}

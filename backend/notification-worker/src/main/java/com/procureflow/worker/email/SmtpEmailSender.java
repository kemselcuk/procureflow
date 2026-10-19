package com.procureflow.worker.email;

import com.procureflow.worker.config.SmtpProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends one claimed email job to the local demo capture endpoint. The sender is fixed to
 * {@code notifications@demo.invalid} and the destination must be the schema-generated
 * {@code <identity UUID>@demo.invalid} address; a mismatch is refused without SMTP I/O. Message
 * metadata is limited to the event type and the canonical request/correlation identifiers, and a
 * stable per-job Message-ID lets retries and the acceptance-before-save duplicate be correlated.
 * Every socket operation is bounded by the validated connect/read/write timeouts.
 */
public class SmtpEmailSender {
    public static final String FROM_ADDRESS = "notifications@demo.invalid";
    public static final String DEMO_DOMAIN = "demo.invalid";

    private final JavaMailSender mailSender;

    public SmtpEmailSender(SmtpProperties properties) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(properties.host());
        impl.setPort(properties.port());
        impl.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties mail = impl.getJavaMailProperties();
        // Plain loopback capture: no authentication and no transport encryption are configured.
        mail.put("mail.smtp.auth", "false");
        mail.put("mail.smtp.starttls.enable", "false");
        mail.put("mail.smtp.connectiontimeout", Long.toString(properties.connectionTimeout().toMillis()));
        mail.put("mail.smtp.timeout", Long.toString(properties.readTimeout().toMillis()));
        mail.put("mail.smtp.writetimeout", Long.toString(properties.writeTimeout().toMillis()));
        this.mailSender = impl;
    }

    /**
     * The resolved JavaMail socket properties. Package-visible so boundary tests can assert the
     * actual settings handed to JavaMail instead of only the validation error string.
     */
    Properties javaMailProperties() {
        return ((JavaMailSenderImpl) mailSender).getJavaMailProperties();
    }

    /** Stable Message-ID: the same job retried or duplicated keeps this exact identifier. */
    public static String messageId(UUID jobId) {
        return "<procureflow-job-" + jobId + "@" + DEMO_DOMAIN + ">";
    }

    public EmailSendResult send(ClaimedEmailJob job) {
        if (!job.recipientAddress().equals(job.identityId() + "@" + DEMO_DOMAIN)) {
            // Defense in depth: the database CHECK already constrains the generated address.
            return EmailSendResult.failure(EmailFailure.RECIPIENT_INVALID);
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(FROM_ADDRESS);
            helper.setTo(job.recipientAddress());
            helper.setSubject("ProcureFlow notification: " + job.eventType());
            helper.setText(body(job), false);
            message.setHeader("Message-ID", messageId(job.jobId()));
            message.setHeader("X-ProcureFlow-Job-Id", job.jobId().toString());
            message.setHeader("X-ProcureFlow-Event-Id", job.eventId().toString());
            message.setHeader("X-ProcureFlow-Correlation-Id", job.correlationId().toString());
            message.setHeader("X-ProcureFlow-Schema-Version", "1");
            mailSender.send(message);
            return EmailSendResult.success();
        } catch (MailException | MessagingException e) {
            return EmailSendResult.failure(classify(e));
        }
    }

    /** Minimal canonical metadata only; never purchase free text, reason or amount. */
    static String body(ClaimedEmailJob job) {
        return "ProcureFlow notification\n\n"
                + "Event type: " + job.eventType() + "\n"
                + "Request ID: " + job.requestId() + "\n"
                + "Correlation ID: " + job.correlationId() + "\n";
    }

    /**
     * Maps a mail exception chain to a fixed sanitized code. Permanent 5xx command/address replies
     * are terminal; 4xx replies stay retryable; connect failures and socket timeouts stay retryable.
     * Provider exception classes are matched reflectively so the worker keeps only the Boot-managed
     * mail starter on its compile classpath.
     */
    static EmailFailure classify(Throwable failure) {
        List<Throwable> candidates = new ArrayList<>();
        collect(failure, candidates);
        if (failure instanceof MailSendException sendException) {
            for (Exception nested : sendException.getMessageExceptions()) {
                collect(nested, candidates);
            }
        }
        for (Throwable candidate : candidates) {
            if (candidate instanceof SendFailedException) {
                Integer returnCode = returnCode(candidate);
                if (returnCode != null) {
                    return returnCode >= 500 ? EmailFailure.SMTP_REJECTED : EmailFailure.SMTP_TRANSIENT;
                }
            }
        }
        for (Throwable candidate : candidates) {
            if (candidate instanceof SocketTimeoutException) {
                return EmailFailure.SMTP_TIMEOUT;
            }
        }
        for (Throwable candidate : candidates) {
            if (candidate instanceof ConnectException || candidate instanceof NoRouteToHostException
                    || candidate instanceof UnknownHostException
                    || "MailConnectException".equals(candidate.getClass().getSimpleName())) {
                return EmailFailure.SMTP_UNREACHABLE;
            }
        }
        return EmailFailure.SMTP_FAILED;
    }

    private static void collect(Throwable start, List<Throwable> candidates) {
        for (Throwable cause = start; cause != null && !candidates.contains(cause); cause = cause.getCause()) {
            candidates.add(cause);
        }
    }

    /** Reflectively reads the SMTP reply code from an Angus send/address failure exception. */
    private static Integer returnCode(Throwable candidate) {
        try {
            return ((Number) candidate.getClass().getMethod("getReturnCode").invoke(candidate)).intValue();
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}

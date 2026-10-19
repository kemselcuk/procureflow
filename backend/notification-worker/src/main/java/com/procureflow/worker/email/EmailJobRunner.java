package com.procureflow.worker.email;

import com.procureflow.worker.config.SmtpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Bounded email-job runner. Each job is claimed (company barrier, exact row lock, eligibility
 * re-read, fencing lease) in a short transaction that commits before any SMTP I/O, so a claim is
 * never taken for work that then waits behind another job's send. A lease whose own send overran it
 * is fenced; a later cycle may reclaim the job and legitimately duplicate the message, which is why
 * every attempt keeps the same stable Message-ID.
 */
public class EmailJobRunner {
    private static final Logger log = LoggerFactory.getLogger(EmailJobRunner.class);

    private final EmailJobStore store;
    private final SmtpEmailSender sender;
    private final SmtpProperties properties;
    private final EmailInstrumentation instrumentation;

    public EmailJobRunner(EmailJobStore store, SmtpEmailSender sender, SmtpProperties properties,
                          EmailInstrumentation instrumentation) {
        this.store = store;
        this.sender = sender;
        this.properties = properties;
        this.instrumentation = instrumentation;
    }

    /** Processes at most {@code work-limit} jobs; each claim commits immediately before its send. */
    public void runOnce() {
        int quarantined;
        try {
            quarantined = store.quarantineExhausted(properties.cleanupBatchSize(), properties.maxAttempts());
        } catch (DataAccessException e) {
            log.warn("Email runner could not sweep exhausted jobs: {}", e.getClass().getSimpleName());
            return;
        }
        if (quarantined > 0) {
            log.warn("Quarantined {} email job(s) that exhausted {} attempts", quarantined, properties.maxAttempts());
        }
        int processed = 0;
        while (processed < properties.workLimit()) {
            EmailJobStore.ClaimResult result;
            try {
                result = store.claimOne(properties);
            } catch (DataAccessException e) {
                // Database outages are retried on the next poll; the claim transaction rolled back.
                log.warn("Email runner could not claim work: {}", e.getClass().getSimpleName());
                return;
            }
            if (result.quarantined() > 0) {
                log.warn("Quarantined {} email job(s) that exhausted {} attempts",
                        result.quarantined(), properties.maxAttempts());
            }
            if (result.skipped() != null) {
                log.info("Skipped ineligible email job id={} reason={}",
                        result.skipped().jobId(), result.skipped().reasonCode());
                processed++;
                continue;
            }
            ClaimedEmailJob job = result.claimed();
            if (job == null) {
                return;
            }
            sendOne(job);
            processed++;
        }
    }

    private void sendOne(ClaimedEmailJob job) {
        // The claim transaction has committed; no database transaction or row lock spans the I/O.
        instrumentation.beforeSend(job);
        EmailSendResult result = sender.send(job);
        if (result.delivered()) {
            // Failure-injection seam: a throw here simulates a worker crash after SMTP acceptance
            // and before the SENT save, which legitimately duplicates the message on retry.
            instrumentation.beforeSentStateSave(job.jobId());
            if (store.markSent(job)) {
                log.info("Sent email job id={} attempt={}", job.jobId(), job.attempt());
            } else {
                log.warn("Ignored stale email completion id={} attempt={} (lease expired or reclaimed)",
                        job.jobId(), job.attempt());
            }
            return;
        }
        long delayMillis = properties.retryDelayMillis(job.attempt());
        boolean saved = store.markFailed(job, result.failure(), delayMillis, properties.maxAttempts());
        if (!saved) {
            log.warn("Ignored stale email failure id={} attempt={} code={} (lease expired or reclaimed)",
                    job.jobId(), job.attempt(), result.failure().code());
        } else if (result.failure().terminal() || job.attempt() >= properties.maxAttempts()) {
            log.warn("Quarantined email job id={} after {} attempts code={}",
                    job.jobId(), job.attempt(), result.failure().code());
        } else {
            log.info("Deferred email job id={} attempt={} code={} retryInMs={}",
                    job.jobId(), job.attempt(), result.failure().code(), delayMillis);
        }
    }
}

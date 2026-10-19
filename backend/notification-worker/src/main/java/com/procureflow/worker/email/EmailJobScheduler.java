package com.procureflow.worker.email;

import org.springframework.scheduling.annotation.Scheduled;

/** Periodic driver; can be disabled for tests or a future externally triggered mode. */
public class EmailJobScheduler {
    private final EmailJobRunner runner;

    public EmailJobScheduler(EmailJobRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${worker.smtp.poll-interval}")
    void poll() {
        runner.runOnce();
    }
}

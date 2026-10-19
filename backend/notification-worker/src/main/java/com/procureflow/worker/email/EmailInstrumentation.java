package com.procureflow.worker.email;

import java.util.UUID;

/**
 * In-process injection points for failure tests. Production wires the no-op implementation; no
 * HTTP or other externally reachable debug surface exists.
 */
public interface EmailInstrumentation {
    /**
     * Invoked after the claim transaction committed and immediately before SMTP I/O. A test can
     * observe that no database lock is held on the job row at this point.
     */
    default void beforeSend(ClaimedEmailJob job) {}

    /**
     * Invoked after SMTP acceptance and before the fenced SENT save; may throw to simulate a crash
     * in the acceptance-before-save window, whose retry legitimately duplicates the message.
     */
    default void beforeSentStateSave(UUID jobId) {}

    EmailInstrumentation NOOP = new EmailInstrumentation() {};
}

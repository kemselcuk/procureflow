package com.procureflow.worker.email;

/**
 * Fixed, sanitized SMTP failure classifications. Only these codes are persisted or logged; broker,
 * mail-server or exception text (which can embed message content or credentials) never is. A
 * permanent rejection is terminal on the first observation because retrying an invalid capture
 * destination cannot succeed; transient failures back off until the attempt limit quarantines them.
 */
public enum EmailFailure {
    SMTP_UNREACHABLE("smtp_unreachable", false),
    SMTP_TIMEOUT("smtp_timeout", false),
    SMTP_TRANSIENT("smtp_transient", false),
    SMTP_REJECTED("smtp_rejected", true),
    SMTP_FAILED("smtp_failed", false),
    RECIPIENT_INVALID("recipient_invalid", true);

    private final String code;
    private final boolean terminal;

    EmailFailure(String code, boolean terminal) {
        this.code = code;
        this.terminal = terminal;
    }

    public String code() {
        return code;
    }

    /** Terminal failures are quarantined immediately instead of being scheduled for retry. */
    public boolean terminal() {
        return terminal;
    }
}

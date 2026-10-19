package com.procureflow.worker.email;

/** Result of one SMTP attempt, deliberately without server-provided text. */
public record EmailSendResult(boolean delivered, EmailFailure failure) {
    public static EmailSendResult success() {
        return new EmailSendResult(true, null);
    }

    public static EmailSendResult failure(EmailFailure failure) {
        return new EmailSendResult(false, failure);
    }
}

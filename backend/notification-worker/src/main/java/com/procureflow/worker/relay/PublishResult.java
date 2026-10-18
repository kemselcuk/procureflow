package com.procureflow.worker.relay;

/** Result of one publish attempt, deliberately without broker-provided text. */
public record PublishResult(boolean delivered, PublishFailure failure) {
    public static PublishResult success() {
        return new PublishResult(true, null);
    }

    public static PublishResult retryable(PublishFailure failure) {
        return new PublishResult(false, failure);
    }
}

package com.procureflow.worker.relay;

/** Fixed sanitized failure classifications; no broker text, payload or credential is persisted. */
public enum PublishFailure {
    BROKER_UNREACHABLE("broker_unreachable"),
    BROKER_NACK("broker_nack"),
    BROKER_RETURNED("broker_returned"),
    PUBLISH_TIMEOUT("publish_timeout");

    private final String code;

    PublishFailure(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

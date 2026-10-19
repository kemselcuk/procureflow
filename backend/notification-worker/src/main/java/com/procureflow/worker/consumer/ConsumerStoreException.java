package com.procureflow.worker.consumer;

/**
 * Transient consumer-store failure: a database read, write or transaction could not complete. The
 * message processor decides the bounded retry/quarantine policy; the store never decides it and
 * never performs broker or SMTP I/O.
 */
public class ConsumerStoreException extends RuntimeException {

    private final String reasonCode;

    public ConsumerStoreException(String reasonCode, Throwable cause) {
        super(reasonCode, cause);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}

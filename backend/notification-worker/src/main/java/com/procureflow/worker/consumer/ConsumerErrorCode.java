package com.procureflow.worker.consumer;

/**
 * Stable, sanitized consumer failure codes. Values match the diagnostic table's
 * {@code ^[a-z0-9_]{1,64}$} constraint and never contain message content.
 *
 * <p>The diagnostic table has no explicit state column, so the quarantine convention is expressed
 * by the code and the attempt counter: a permanent code is terminal at its first record, while
 * {@link #MATERIALIZATION_FAILED} and {@link #UNHANDLED_CONSUMER_FAILURE} are the bounded transient
 * codes and become terminal once their recorded {@code attempts} value reaches
 * {@code worker.consumer.max-attempts}. Any diagnostic row satisfying one of those rules is a
 * durable quarantine record.
 */
public enum ConsumerErrorCode {
    /** Unparseable, oversized or structurally invalid input; never linked to a canonical event. */
    MALFORMED_MESSAGE("malformed_message", true),
    /** Transport messageId, type, schemaVersion header or correlation disagrees with the envelope. */
    TRANSPORT_IDENTITY_MISMATCH("transport_identity_mismatch", true),
    /** The transport and envelope agree on a schema version the consumer does not implement. */
    UNSUPPORTED_SCHEMA_VERSION("unsupported_schema_version", true),
    /** The envelope names an event type outside the frozen schema-v1 set. */
    UNSUPPORTED_EVENT_TYPE("unsupported_event_type", true),
    /** The from/to status pair is not a legal transition for the announced event type. */
    INVALID_TRANSITION("invalid_transition", true),
    /** No canonical company-scoped outbox event exists for the announced identity. */
    CANONICAL_EVENT_MISSING("canonical_event_missing", true),
    /** The received envelope differs semantically from the canonical snapshot. */
    CANONICAL_ENVELOPE_MISMATCH("canonical_envelope_mismatch", true),
    /** The canonical envelope contradicts the independent recipient table snapshot. */
    RECIPIENT_SNAPSHOT_MISMATCH("recipient_snapshot_mismatch", true),
    /** A transient database or transaction failure while materializing; bounded by attempts. */
    MATERIALIZATION_FAILED("materialization_failed", false),
    /**
     * An unexpected runtime failure escaped the handled consumer paths. The throwable is never
     * recorded or logged; only this fixed code and the sanitized fingerprint carry the evidence.
     * Bounded by attempts like the other transient code.
     */
    UNHANDLED_CONSUMER_FAILURE("unhandled_consumer_failure", false);

    private final String code;
    private final boolean permanent;

    ConsumerErrorCode(String code, boolean permanent) {
        this.code = code;
        this.permanent = permanent;
    }

    public String code() {
        return code;
    }

    public boolean permanent() {
        return permanent;
    }

    /** A permanent code is terminal immediately; a transient code at the configured attempt cap. */
    public boolean terminalAt(int attempts, int maxAttempts) {
        return permanent || attempts >= maxAttempts;
    }
}

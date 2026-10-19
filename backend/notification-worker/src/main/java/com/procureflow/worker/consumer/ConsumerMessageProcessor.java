package com.procureflow.worker.consumer;

import com.procureflow.events.RequestEvent;
import com.procureflow.worker.config.ConsumerProperties;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/**
 * Consumer pipeline for one broker delivery. It validates the untrusted envelope, compares it with
 * the canonical company-scoped database snapshot, materializes the inbox/delivery/in-app/initial
 * email-job rows in one short transaction and decides whether the delivery may be acknowledged.
 *
 * <p><strong>Authority.</strong> The broker message never selects recipients or content. After
 * parsing the claim and loading the canonical event by its scoped identity, every field including
 * recipients, channels, transition, time and correlation is compared semantically; the recipient
 * table is compared independently. A permanent mismatch is quarantined, never materialized.
 *
 * <p><strong>Bounded retry and durable quarantine.</strong> A transient database failure rolls the
 * materialization transaction back, then one sanitized diagnostic row is written in a separate short
 * transaction and the delivery is retried at most {@code worker.consumer.max-attempts} times with an
 * interruptible exponential backoff between attempts. The durable attempt count, not only the
 * RabbitMQ redelivered flag, limits a redelivery after a crash. Once the cap is reached, the
 * diagnostic row is the quarantine record and the delivery is acknowledged; a later redelivery of
 * such an exhausted message is recorded as an observed retry without re-attempting materialization.
 * If neither normal effects nor a quarantine record can be committed, the method returns
 * {@code false} and the delivery stays unacknowledged for recovery. Interruption (shutdown)
 * also leaves the delivery unacknowledged.
 *
 * <p><strong>The in-process attempt counter is never terminal proof.</strong> An ACK without effects
 * requires committed evidence: a diagnostic row for the exact fingerprint whose stored code and
 * attempt value reached the terminal threshold. When the diagnostic write itself fails, the local
 * counter may run out while the durable counter stays below the cap; the method then returns
 * {@code false} instead of acknowledging, so a worker crash, restart or the listener's retained
 * delivery retry (see {@link NotificationQueueListener}) can still complete the attempt budget.
 *
 * <p>The diagnostic channel column is not channel-specific for this consumer: event-level failures
 * are recorded with the fixed {@code IN_APP} convention because the materialization transaction
 * covers all channels at once.
 */
@Component
public class ConsumerMessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(ConsumerMessageProcessor.class);

    private final ConsumerProperties properties;
    private final ConsumerStore store;
    private final ConsumerEnvelopeParser parser;
    private final ConsumerInstrumentation instrumentation;

    ConsumerMessageProcessor(ConsumerProperties properties, ConsumerStore store, ConsumerEnvelopeParser parser,
                             ConsumerInstrumentation instrumentation) {
        this.properties = properties;
        this.store = store;
        this.parser = parser;
        this.instrumentation = instrumentation;
    }

    /** Returns true when the delivery may be acknowledged; false keeps it for broker recovery. */
    public boolean process(Message message) {
        MessageProperties transport = message.getMessageProperties();
        byte[] body = message.getBody();
        if (body.length > properties.maxBodyBytes()) {
            return quarantine(Scope.raw(body), ConsumerErrorCode.MALFORMED_MESSAGE);
        }
        ConsumerEnvelopeParser.Result parsed = parser.parse(body);
        if (!parsed.valid()) {
            return quarantine(Scope.raw(body), ConsumerErrorCode.MALFORMED_MESSAGE);
        }
        ConsumerEnvelope envelope = parsed.envelope();
        String rawHash = ConsumerFailureFingerprint.forRawBody(body, ConsumerErrorCode.MATERIALIZATION_FAILED);
        String eventHash = ConsumerFailureFingerprint.forEvent(envelope.companyId(), envelope.eventId(),
                ConsumerErrorCode.MATERIALIZATION_FAILED);
        int durableAttempts;
        try {
            durableAttempts = Math.max(attemptsFor(rawHash), attemptsFor(eventHash));
        } catch (ConsumerStoreException e) {
            log.warn("Consumer attempt evidence unavailable ({}); a fresh bounded local attempt budget applies",
                    e.reasonCode());
            durableAttempts = 0;
        }
        int unrecordedFailures = 0;
        CanonicalEvent canonical = null;
        while (true) {
            // Only a committed diagnostic row that reached the terminal threshold may skip
            // materialization and acknowledge. The local counter below is a work budget, never
            // terminal evidence: if the diagnostic write fails, no ACK may be derived from it.
            if (ConsumerErrorCode.MATERIALIZATION_FAILED.terminalAt(durableAttempts, properties.maxAttempts())) {
                return closeExhausted(envelope, rawHash, eventHash);
            }
            try {
                canonical = store.findCanonical(envelope.companyId(), envelope.eventId()).orElse(null);
                ConsumerEnvelope canonicalEnvelope = validated(transport, envelope, canonical);
                ConsumerStore.MaterializationResult result =
                        store.materialize(canonical, canonicalEnvelope.recipients());
                logMaterialized(envelope, result);
                instrumentation.afterMaterializationCommit(envelope.eventId());
                return true;
            } catch (PermanentFailure failure) {
                return quarantine(Scope.of(body, envelope, canonical), failure.code());
            } catch (ConsumerStoreException e) {
                ConsumerErrorCode code = ConsumerErrorCode.MATERIALIZATION_FAILED;
                Scope scope = Scope.of(body, envelope, canonical);
                boolean terminalCommitted = false;
                try {
                    int persisted = store.recordDiagnostic(scope.fingerprint(code), scope.companyId(),
                            scope.eventId(), code);
                    unrecordedFailures = 0;
                    terminalCommitted = code.terminalAt(persisted, properties.maxAttempts());
                    if (terminalCommitted) {
                        log.warn("Materialization attempt budget exhausted and its durable quarantine record "
                                + "committed for event={}; acknowledging without effects", envelope.eventId());
                    }
                } catch (ConsumerStoreException recordFailure) {
                    unrecordedFailures++;
                    log.warn("Transient consumer failure could not be recorded ({}/{} reason={}); the local "
                            + "attempt budget is not terminal evidence", unrecordedFailures,
                            properties.maxAttempts(), recordFailure.reasonCode());
                    if (unrecordedFailures >= properties.maxAttempts()) {
                        log.error("Neither normal effects nor a quarantine record could be committed for "
                                + "event={}; leaving the delivery unacknowledged for recovery",
                                envelope.eventId());
                        return false;
                    }
                }
                if (terminalCommitted) {
                    return true;
                }
                durableAttempts = durableAttempts(eventHash, rawHash, durableAttempts);
                if (!interruptibleSleep(properties.retryDelayMillis(durableAttempts + 1))) {
                    return false;
                }
            }
        }
    }

    /** Re-reads the durable attempt evidence, keeping the last known count when the read fails. */
    private int durableAttempts(String eventHash, String rawHash, int fallback) {
        try {
            return Math.max(fallback, Math.max(attemptsFor(eventHash), attemptsFor(rawHash)));
        } catch (ConsumerStoreException readFailure) {
            log.warn("Consumer attempt evidence re-read failed ({}); keeping the last durable count",
                    readFailure.reasonCode());
            return fallback;
        }
    }

    /** Durable attempts for the transient code at one fingerprint, exact code verified. */
    private int attemptsFor(String payloadHash) {
        return store.diagnosticEvidence(payloadHash)
                .filter(evidence -> ConsumerErrorCode.MATERIALIZATION_FAILED.code().equals(evidence.errorCode()))
                .map(ConsumerStore.DiagnosticEvidence::attempts)
                .orElse(0);
    }

    private ConsumerEnvelope validated(MessageProperties transport, ConsumerEnvelope received,
                                       CanonicalEvent canonical) {
        if (!ConsumerEnvelopeRules.transportMatches(transport, received)) {
            throw new PermanentFailure(ConsumerErrorCode.TRANSPORT_IDENTITY_MISMATCH);
        }
        if (received.schemaVersion() != 1) {
            throw new PermanentFailure(ConsumerErrorCode.UNSUPPORTED_SCHEMA_VERSION);
        }
        if (!ConsumerEnvelopeRules.supportedType(received.type())) {
            throw new PermanentFailure(ConsumerErrorCode.UNSUPPORTED_EVENT_TYPE);
        }
        if (!ConsumerEnvelopeRules.legalTransition(received.type(), received.transition())) {
            throw new PermanentFailure(ConsumerErrorCode.INVALID_TRANSITION);
        }
        if (canonical == null) {
            throw new PermanentFailure(ConsumerErrorCode.CANONICAL_EVENT_MISSING);
        }
        // The stored envelope is read as text from jsonb; parse it with the same strict rules so an
        // unparseable canonical snapshot cannot be silently trusted or half-compared.
        ConsumerEnvelopeParser.Result canonicalParsed =
                parser.parse(canonical.envelopeJson().getBytes(StandardCharsets.UTF_8));
        if (!canonicalParsed.valid()
                || !ConsumerEnvelopeRules.matchesCanonical(received, canonicalParsed.envelope())) {
            throw new PermanentFailure(ConsumerErrorCode.CANONICAL_ENVELOPE_MISMATCH);
        }
        if (!ConsumerEnvelopeRules.recipientTableMatches(canonicalParsed.envelope(), canonical.recipients())) {
            throw new PermanentFailure(ConsumerErrorCode.RECIPIENT_SNAPSHOT_MISMATCH);
        }
        return canonicalParsed.envelope();
    }

    private void logMaterialized(ConsumerEnvelope envelope, ConsumerStore.MaterializationResult result) {
        if (result.duplicate()) {
            log.info("Suppressed duplicate delivery event={} company={} processedAt={}", envelope.eventId(),
                    envelope.companyId(), result.processedAt());
        } else {
            log.info("Materialized event={} company={} deliveries={} skipped={} inApp={} emailJobs={}",
                    envelope.eventId(), envelope.companyId(), result.materialized(), result.skipped(),
                    result.inApp(), result.emailJobs());
        }
    }

    /**
     * Writes the durable quarantine record and reports whether the delivery may be acknowledged. If
     * the write fails, only an already committed record for the exact fingerprint and code whose
     * attempt value is terminal is accepted as evidence; otherwise the delivery stays unacknowledged.
     */
    private boolean quarantine(Scope scope, ConsumerErrorCode code) {
        String fingerprint = scope.fingerprint(code);
        try {
            int attempts = store.recordDiagnostic(fingerprint, scope.companyId(), scope.eventId(), code);
            log.warn("Quarantined notification message code={} fingerprint={} attempts={} event={} company={}",
                    code.code(), fingerprint, attempts, scope.eventId(), scope.companyId());
            return true;
        } catch (ConsumerStoreException e) {
            log.warn("Could not persist quarantine record code={} fingerprint={} reason={}", code.code(),
                    fingerprint, e.reasonCode());
            try {
                boolean terminalRecord = store.diagnosticEvidence(fingerprint)
                        .filter(evidence -> code.code().equals(evidence.errorCode()))
                        .map(evidence -> code.terminalAt(evidence.attempts(), properties.maxAttempts()))
                        .orElse(false);
                if (terminalRecord) {
                    log.warn("An identical terminal durable quarantine record already exists; acknowledging "
                            + "this delivery");
                    return true;
                }
            } catch (ConsumerStoreException readFailure) {
                log.warn("Quarantine evidence check failed: {}", readFailure.reasonCode());
            }
            return false;
        }
    }

    /**
     * Durable evidence says the transient attempt budget was already exhausted. Observe the
     * redelivery by incrementing the exhausted record and acknowledge without touching any effect;
     * this is what prevents a poison redelivery loop after a restart. The re-read requires the exact
     * fingerprint and code at or above the terminal threshold, not merely any existing row.
     */
    private boolean closeExhausted(ConsumerEnvelope envelope, String rawHash, String eventHash) {
        int maxAttempts = properties.maxAttempts();
        try {
            boolean eventExhausted = terminalEvidenceAt(eventHash, maxAttempts);
            boolean rawExhausted = terminalEvidenceAt(rawHash, maxAttempts);
            if (!eventExhausted && !rawExhausted) {
                log.warn("Attempt evidence changed before the terminal record for event={}; leaving the "
                        + "delivery unacknowledged", envelope.eventId());
                return false;
            }
            if (eventExhausted) {
                store.recordDiagnostic(eventHash, envelope.companyId(), envelope.eventId(),
                        ConsumerErrorCode.MATERIALIZATION_FAILED);
            }
            if (rawExhausted) {
                store.recordDiagnostic(rawHash, null, null, ConsumerErrorCode.MATERIALIZATION_FAILED);
            }
            log.warn("Durable attempt evidence already exhausted for event={}; observed redelivery without "
                    + "re-materializing", envelope.eventId());
            return true;
        } catch (ConsumerStoreException e) {
            log.warn("Could not observe the exhausted quarantine record for event={} ({}); leaving the delivery "
                    + "unacknowledged", envelope.eventId(), e.reasonCode());
            return false;
        }
    }

    /** True when the exact fingerprint has a committed transient-code record at the terminal cap. */
    private boolean terminalEvidenceAt(String payloadHash, int maxAttempts) {
        return store.diagnosticEvidence(payloadHash)
                .filter(evidence -> ConsumerErrorCode.MATERIALIZATION_FAILED.code().equals(evidence.errorCode()))
                .map(evidence -> ConsumerErrorCode.MATERIALIZATION_FAILED.terminalAt(evidence.attempts(),
                        maxAttempts))
                .orElse(false);
    }

    private boolean interruptibleSleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Consumer retry interrupted; leaving the delivery unacknowledged for broker recovery");
            return false;
        }
    }

    /**
     * Diagnostic scope: event-scoped as soon as the canonical snapshot is known, otherwise raw scoped
     * with NULL company/event because no canonical event exists to reference.
     */
    private record Scope(byte[] body, UUID companyId, UUID eventId, boolean canonical) {

        static Scope raw(byte[] body) {
            return new Scope(body, null, null, false);
        }

        static Scope of(byte[] body, ConsumerEnvelope envelope, CanonicalEvent canonical) {
            return canonical == null ? raw(body)
                    : new Scope(body, envelope.companyId(), envelope.eventId(), true);
        }

        String fingerprint(ConsumerErrorCode code) {
            return canonical ? ConsumerFailureFingerprint.forEvent(companyId, eventId, code)
                    : ConsumerFailureFingerprint.forRawBody(body, code);
        }
    }

    /** Internal control flow for a deterministic validation failure; no stack trace is needed. */
    private static final class PermanentFailure extends RuntimeException {
        private final ConsumerErrorCode code;

        PermanentFailure(ConsumerErrorCode code) {
            super(code.code(), null, false, false);
            this.code = code;
        }

        ConsumerErrorCode code() {
            return code;
        }
    }
}

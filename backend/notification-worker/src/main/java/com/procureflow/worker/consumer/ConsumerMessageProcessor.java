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
 * interruptible exponential backoff between attempts. An unexpected runtime failure that escapes the
 * normal validation and store paths is bounded by the same attempt mechanism under
 * {@link ConsumerErrorCode#UNHANDLED_CONSUMER_FAILURE}; its throwable is never inspected, logged or
 * persisted. The durable attempt count, not only the RabbitMQ redelivered flag, limits a redelivery
 * after a crash. Once the cap is reached, the diagnostic row is the quarantine record and the
 * delivery is acknowledged; a later redelivery of such an exhausted message is recorded as an
 * observed retry without re-attempting materialization. If neither normal effects nor a quarantine
 * record can be committed, the method returns {@code false} and the delivery stays unacknowledged for
 * recovery. Interruption (shutdown) also leaves the delivery unacknowledged.
 *
 * <p><strong>The in-process attempt counter is never terminal proof.</strong> An ACK without effects
 * requires committed evidence: a diagnostic row for the exact fingerprint whose stored code and
 * attempt value reached the terminal threshold. When the diagnostic write itself fails, the local
 * counter may run out while the durable counter stays below the cap; the method then returns
 * {@code false} instead of acknowledging, so a worker crash, restart or the listener's retained
 * delivery retry (see {@link NotificationQueueListener}, still the final safety net for exceptions
 * thrown outside this method) can still complete the attempt budget.
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
        ConsumerEnvelopeParser.Result parsed;
        try {
            parsed = parser.parse(body);
        } catch (RuntimeException unexpected) {
            // The parser contract is to report invalid input, so an escaping throwable is a real
            // unexpected failure. No envelope identity exists yet; retain the delivery and record
            // bounded raw-body evidence instead of acknowledging unread input.
            return unparsedFailure(body);
        }
        if (!parsed.valid()) {
            return quarantine(Scope.raw(body), ConsumerErrorCode.MALFORMED_MESSAGE);
        }
        ConsumerEnvelope envelope = parsed.envelope();
        String rawHash = ConsumerFailureFingerprint.forRawBody(body, ConsumerErrorCode.MATERIALIZATION_FAILED);
        String eventHash = ConsumerFailureFingerprint.forEvent(envelope.companyId(), envelope.eventId(),
                ConsumerErrorCode.MATERIALIZATION_FAILED);
        String unhandledRawHash = ConsumerFailureFingerprint.forRawBody(body,
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        String unhandledEventHash = ConsumerFailureFingerprint.forEvent(envelope.companyId(), envelope.eventId(),
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        int durableAttempts = transientAttempts(eventHash, rawHash, ConsumerErrorCode.MATERIALIZATION_FAILED);
        int unhandledAttempts = transientAttempts(unhandledEventHash, unhandledRawHash,
                ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
        int unrecordedFailures = 0;
        CanonicalEvent canonical = null;
        while (true) {
            // Only a committed diagnostic row that reached the terminal threshold may skip
            // materialization and acknowledge. The local counter below is a work budget, never
            // terminal evidence: if the diagnostic write fails, no ACK may be derived from it.
            if (ConsumerErrorCode.MATERIALIZATION_FAILED.terminalAt(durableAttempts, properties.maxAttempts())) {
                return closeExhausted(envelope, rawHash, eventHash, ConsumerErrorCode.MATERIALIZATION_FAILED);
            }
            if (ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE.terminalAt(unhandledAttempts,
                    properties.maxAttempts())) {
                return closeExhausted(envelope, unhandledRawHash, unhandledEventHash,
                        ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE);
            }
            ConsumerStore.MaterializationResult result;
            try {
                canonical = store.findCanonical(envelope.companyId(), envelope.eventId()).orElse(null);
                ConsumerEnvelope canonicalEnvelope = validated(transport, envelope, canonical);
                result = store.materialize(canonical, canonicalEnvelope.recipients());
            } catch (PermanentFailure failure) {
                return quarantine(Scope.of(body, envelope, canonical), failure.code());
            } catch (ConsumerStoreException e) {
                FailureRecord recorded = recordFailure(Scope.of(body, envelope, canonical),
                        ConsumerErrorCode.MATERIALIZATION_FAILED, envelope.eventId(), unrecordedFailures,
                        durableAttempts);
                if (recorded.terminal()) {
                    return true;
                }
                if (recorded.exhausted()) {
                    return false;
                }
                unrecordedFailures = recorded.unrecordedFailures();
                durableAttempts = durableAttempts(eventHash, rawHash,
                        ConsumerErrorCode.MATERIALIZATION_FAILED, recorded.durableAttempts());
                if (!interruptibleSleep(properties.retryDelayMillis(durableAttempts + 1))) {
                    return false;
                }
                continue;
            } catch (RuntimeException unexpected) {
                // Never log the throwable, body, headers or raw exception text: an unexpected
                // failure can embed any of them. The fixed code and sanitized fingerprint are the
                // only durable evidence, written in a short transaction of their own.
                FailureRecord recorded = recordFailure(Scope.of(body, envelope, canonical),
                        ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE, envelope.eventId(), unrecordedFailures,
                        unhandledAttempts);
                if (recorded.terminal()) {
                    return true;
                }
                if (recorded.exhausted()) {
                    return false;
                }
                unrecordedFailures = recorded.unrecordedFailures();
                unhandledAttempts = durableAttempts(unhandledEventHash, unhandledRawHash,
                        ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE, recorded.durableAttempts());
                if (!interruptibleSleep(properties.retryDelayMillis(unhandledAttempts + 1))) {
                    return false;
                }
                continue;
            }
            logMaterialized(envelope, result);
            instrumentation.afterMaterializationCommit(envelope.eventId());
            return true;
        }
    }

    /**
     * Re-reads the durable attempt evidence for one transient code, keeping the last known count when
     * the read fails.
     */
    private int durableAttempts(String eventHash, String rawHash, ConsumerErrorCode code, int fallback) {
        try {
            return Math.max(fallback, Math.max(attemptsFor(eventHash, code), attemptsFor(rawHash, code)));
        } catch (ConsumerStoreException readFailure) {
            log.warn("Consumer attempt evidence re-read failed for code={} ({}); keeping the last durable count",
                    code.code(), readFailure.reasonCode());
            return fallback;
        }
    }

    /** Reads the larger durable attempt count of the event/raw fingerprints for one transient code. */
    private int transientAttempts(String eventHash, String rawHash, ConsumerErrorCode code) {
        try {
            return Math.max(attemptsFor(eventHash, code), attemptsFor(rawHash, code));
        } catch (ConsumerStoreException e) {
            log.warn("Consumer attempt evidence unavailable for code={} ({}); a fresh bounded local attempt "
                    + "budget applies", code.code(), e.reasonCode());
            return 0;
        }
    }

    /** Durable attempts for the exact transient code at one fingerprint, code verified. */
    private int attemptsFor(String payloadHash, ConsumerErrorCode code) {
        return store.diagnosticEvidence(payloadHash)
                .filter(evidence -> code.code().equals(evidence.errorCode()))
                .map(ConsumerStore.DiagnosticEvidence::attempts)
                .orElse(0);
    }

    /**
     * Records one sanitized failure in a short transaction of its own and reports the durable
     * decision. The throwable is never inspected or logged; only the fixed code, its fingerprint and
     * the durable attempt value are used. A record that cannot be written is never terminal evidence:
     * after the configured number of consecutive write failures the delivery stays unacknowledged.
     */
    private FailureRecord recordFailure(Scope scope, ConsumerErrorCode code, UUID eventId,
                                        int unrecordedFailures, int durableAttempts) {
        String fingerprint = scope.fingerprint(code);
        try {
            int persisted = store.recordDiagnostic(fingerprint, scope.companyId(), scope.eventId(), code);
            boolean terminal = code.terminalAt(persisted, properties.maxAttempts());
            if (terminal) {
                log.warn("Consumer failure attempt budget exhausted and its durable quarantine record committed "
                        + "for code={} fingerprint={} event={}; acknowledging without effects", code.code(),
                        fingerprint, eventId);
            } else {
                log.warn("Recorded transient consumer failure code={} fingerprint={} event={} attempts={}",
                        code.code(), fingerprint, eventId, persisted);
            }
            return new FailureRecord(terminal, false, 0, Math.max(durableAttempts, persisted));
        } catch (ConsumerStoreException recordFailure) {
            int failures = unrecordedFailures + 1;
            log.warn("Transient consumer failure could not be recorded code={} ({}/{} reason={}); the local "
                    + "attempt budget is not terminal evidence", code.code(), failures, properties.maxAttempts(),
                    recordFailure.reasonCode());
            if (failures >= properties.maxAttempts()) {
                log.error("Neither normal effects nor a quarantine record could be committed for code={} "
                        + "event={}; leaving the delivery unacknowledged for recovery", code.code(), eventId);
                return new FailureRecord(false, true, failures, durableAttempts);
            }
            return new FailureRecord(false, false, failures, durableAttempts);
        }
    }

    /**
     * Bounded raw-scope path for an unexpected parser failure, where no envelope identity exists.
     * Each retained-delivery cycle records one attempt; the delivery is acknowledged only when that
     * short transaction committed the terminal attempt value, and a failed record leaves it
     * unacknowledged.
     */
    private boolean unparsedFailure(byte[] body) {
        ConsumerErrorCode code = ConsumerErrorCode.UNHANDLED_CONSUMER_FAILURE;
        Scope scope = Scope.raw(body);
        String fingerprint = scope.fingerprint(code);
        try {
            int attempts = store.recordDiagnostic(fingerprint, null, null, code);
            boolean terminal = code.terminalAt(attempts, properties.maxAttempts());
            if (terminal) {
                log.warn("Unhandled consumer failure before envelope parsing reached the durable attempt cap; "
                        + "acknowledging without effects");
            } else {
                log.warn("Recorded unhandled consumer failure before envelope parsing code={} fingerprint={} "
                        + "attempts={}", code.code(), fingerprint, attempts);
            }
            return terminal;
        } catch (ConsumerStoreException e) {
            log.warn("Unhandled consumer failure before envelope parsing could not be recorded ({})",
                    e.reasonCode());
            return false;
        }
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
    private boolean closeExhausted(ConsumerEnvelope envelope, String rawHash, String eventHash,
                                   ConsumerErrorCode code) {
        int maxAttempts = properties.maxAttempts();
        try {
            boolean eventExhausted = terminalEvidenceAt(eventHash, code, maxAttempts);
            boolean rawExhausted = terminalEvidenceAt(rawHash, code, maxAttempts);
            if (!eventExhausted && !rawExhausted) {
                log.warn("Attempt evidence changed before the terminal record for code={} event={}; leaving the "
                        + "delivery unacknowledged", code.code(), envelope.eventId());
                return false;
            }
            if (eventExhausted) {
                store.recordDiagnostic(eventHash, envelope.companyId(), envelope.eventId(), code);
            }
            if (rawExhausted) {
                store.recordDiagnostic(rawHash, null, null, code);
            }
            log.warn("Durable attempt evidence already exhausted for code={} event={}; observed redelivery "
                    + "without re-materializing", code.code(), envelope.eventId());
            return true;
        } catch (ConsumerStoreException e) {
            log.warn("Could not observe the exhausted quarantine record for code={} event={} ({}); leaving the "
                    + "delivery unacknowledged", code.code(), envelope.eventId(), e.reasonCode());
            return false;
        }
    }

    /** True when the exact fingerprint has a committed transient-code record at the terminal cap. */
    private boolean terminalEvidenceAt(String payloadHash, ConsumerErrorCode code, int maxAttempts) {
        return store.diagnosticEvidence(payloadHash)
                .filter(evidence -> code.code().equals(evidence.errorCode()))
                .map(evidence -> code.terminalAt(evidence.attempts(), maxAttempts))
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

    /** Outcome of one durable diagnostic-write attempt for a transient consumer failure. */
    private record FailureRecord(boolean terminal, boolean exhausted, int unrecordedFailures,
                                 int durableAttempts) {
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

package com.procureflow.worker.relay;

import java.util.UUID;

/**
 * Immutable snapshot of a leased dispatch row joined with its canonical outbox event.
 *
 * @param eventId stable event UUID; also the AMQP message id
 * @param companyId tenant owning the event and dispatch row
 * @param attempt number of the current claim, already incremented in the database
 * @param leaseToken random fencing token issued by the database for this claim
 * @param correlationId canonical command correlation UUID
 * @param eventType schema-v1 event type, also used as the routing key
 * @param envelopeJson exact canonical envelope text stored in the immutable outbox row
 */
public record ClaimedEvent(UUID eventId, UUID companyId, int attempt, UUID leaseToken, UUID correlationId,
                           String eventType, String envelopeJson) {}

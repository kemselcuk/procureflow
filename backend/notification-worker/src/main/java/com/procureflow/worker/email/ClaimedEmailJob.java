package com.procureflow.worker.email;

import java.util.UUID;

/**
 * Immutable snapshot of one claimed email job, joined with the canonical outbox metadata used for
 * the message envelope. It intentionally carries no purchase free text, amount or user-supplied
 * address: the destination is the generated {@code <identity UUID>@demo.invalid} column and the
 * message body is built only from event type/request/correlation identifiers.
 *
 * @param jobId email_job primary key; also the stable Message-ID basis
 * @param companyId tenant owning the job and the canonical event
 * @param eventId immutable outbox event id
 * @param identityId captured recipient identity
 * @param membershipId captured membership verified against the company barrier
 * @param recipientAddress generated {@code identityId@demo.invalid} destination
 * @param attempt number of the current claim, already incremented in the database
 * @param leaseToken random fencing token issued by the database for this claim
 * @param eventType schema-v1 canonical event type
 * @param requestId canonical purchase request id
 * @param correlationId canonical command correlation id
 */
public record ClaimedEmailJob(UUID jobId, UUID companyId, UUID eventId, UUID identityId, UUID membershipId,
                              String recipientAddress, int attempt, UUID leaseToken, String eventType,
                              UUID requestId, UUID correlationId) {}

package com.procureflow.worker.consumer;

import com.procureflow.events.RequestEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Parsed but not-yet-trusted schema-v1 envelope. Values are typed only after strict shape checks;
 * this record keeps the shared {@link RequestEvent} transition and recipient types so the consumer
 * validates against the same frozen contract the producer writes.
 */
public record ConsumerEnvelope(UUID eventId, int schemaVersion, String type, UUID companyId, UUID requestId,
                               UUID correlationId, Instant occurredAt, RequestEvent.Transition transition,
                               List<RequestEvent.Recipient> recipients) {

    public ConsumerEnvelope {
        recipients = List.copyOf(recipients);
    }
}

package com.procureflow.worker.consumer;

import java.util.List;
import java.util.UUID;

/**
 * Immutable company-scoped canonical snapshot loaded from {@code outbox_event} and
 * {@code outbox_recipient}. It is the only authority for recipients, channels and transition data;
 * the broker message is compared against it and never selects recipients itself.
 */
public record CanonicalEvent(UUID companyId, UUID eventId, String envelopeJson, List<CanonicalRecipient> recipients) {

    public CanonicalEvent {
        recipients = List.copyOf(recipients);
    }

    /** One captured recipient row. The boolean flags are the independent channel snapshot. */
    public record CanonicalRecipient(UUID identityId, UUID membershipId, boolean inApp, boolean email) {
    }
}

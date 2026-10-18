package com.procureflow.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable schema-v1 notification contract; contains no free-text purchase content. */
public record RequestEvent(UUID eventId, int schemaVersion, String type, UUID companyId, UUID requestId,
                           UUID correlationId, Instant occurredAt, Transition payload, List<Recipient> recipients) {
    public RequestEvent {
        Objects.requireNonNull(eventId); Objects.requireNonNull(companyId); Objects.requireNonNull(requestId);
        Objects.requireNonNull(correlationId); Objects.requireNonNull(occurredAt); Objects.requireNonNull(payload);
        if (schemaVersion != 1 || !List.of("request.submitted", "request.department_approved", "request.approved",
                "request.rejected", "request.cancelled").contains(type)) throw new IllegalArgumentException("Unsupported event schema/type");
        recipients = List.copyOf(recipients);
        if (recipients.stream().map(Recipient::identityId).distinct().count() != recipients.size())
            throw new IllegalArgumentException("Duplicate recipient identity");
    }

    public record Transition(String fromStatus, String toStatus, long requestVersion, UUID actorIdentityId) {}
    public record Recipient(UUID identityId, UUID membershipId, List<Channel> channels) {
        public Recipient {
            Objects.requireNonNull(identityId); Objects.requireNonNull(membershipId);
            channels = List.copyOf(channels);
        }
    }
    public enum Channel { IN_APP, EMAIL }
}

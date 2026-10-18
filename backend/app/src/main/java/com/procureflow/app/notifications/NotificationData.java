package com.procureflow.app.notifications;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationData {
    private NotificationData() {}

    /**
     * One in-app notification of the current identity. Delivery metadata (event type, request,
     * correlation and occurrence) is joined from the company-scoped outbox event; purchase free
     * text, recipient addresses and the raw envelope are never exposed.
     */
    public record Item(UUID id, UUID eventId, String eventType, UUID requestId, UUID correlationId,
                       Instant occurredAt, Instant createdAt, Instant readAt) {}

    /** One page of own-identity notifications; the explicit schema name keeps it distinct from the request page. */
    @Schema(name = "NotificationPage")
    public record Page(int page, int size, long total, List<Item> items) {}

    public record Read(UUID id, Instant readAt) {}

    public record Dispatch(long pending, long leased, long published, long quarantined) {}

    public record Email(long pending, long leased, long sent, long quarantined, long skipped) {}

    public record Deliveries(long materialized, long skipped) {}

    /** Current-company delivery backlog: counts by state/outcome only, never raw errors or recipients. */
    public record Backlog(UUID companyId, Dispatch dispatch, Email email, Deliveries deliveries, long inboxTotal) {}
}

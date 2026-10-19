package com.procureflow.app.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuditData {
    private AuditData() {}

    /**
     * One immutable audit metadata record of the current company. The contract exposes only
     * references and occurrence metadata: request content, identity claims, e-mail addresses and
     * raw payloads are never part of this response.
     */
    @Schema(name = "AuditEntry")
    public record Entry(UUID id, String action, UUID entityId, UUID actorIdentityId,
                        UUID actorMembershipId, UUID correlationId, Instant occurredAt) {}

    /** One page of current-company audit metadata; the explicit schema name keeps it distinct from other pages. */
    @Schema(name = "AuditPage")
    public record Page(int page, int size, long total, List<Entry> items) {}
}

package com.procureflow.worker.consumer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small schema-v1 JSON sample builder for parser/rules unit tests (no services involved). */
final class ConsumerEnvelopes {
    static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID COMPANY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID REQUEST = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID CORRELATION = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID ACTOR = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID IDENTITY = UUID.fromString("66666666-6666-6666-6666-666666666666");
    static final UUID MEMBERSHIP = UUID.fromString("77777777-7777-7777-7777-777777777777");
    static final Instant OCCURRED_AT = Instant.parse("2026-10-18T12:34:56.789012Z");

    private ConsumerEnvelopes() {
    }

    static String valid() {
        return json(EVENT, 1, "request.submitted", COMPANY, REQUEST, CORRELATION, OCCURRED_AT, "DRAFT",
                "AWAITING_DEPARTMENT_APPROVAL", 1, ACTOR,
                List.of(new Recipient(IDENTITY, MEMBERSHIP, List.of("IN_APP", "EMAIL"))));
    }

    static String json(UUID eventId, int schemaVersion, String type, UUID companyId, UUID requestId,
                       UUID correlationId, Instant occurredAt, String fromStatus, String toStatus,
                       long requestVersion, UUID actorIdentityId, List<Recipient> recipients) {
        List<String> recipientJson = new ArrayList<>();
        for (Recipient recipient : recipients) {
            List<String> channels = recipient.channels().stream().map(channel -> "\"" + channel + "\"").toList();
            recipientJson.add("{\"identityId\":\"%s\",\"membershipId\":\"%s\",\"channels\":[%s]}"
                    .formatted(recipient.identityId(), recipient.membershipId(), String.join(",", channels)));
        }
        return ("{\"eventId\":\"%s\",\"schemaVersion\":%d,\"type\":\"%s\",\"companyId\":\"%s\","
                + "\"requestId\":\"%s\",\"correlationId\":\"%s\",\"occurredAt\":\"%s\","
                + "\"payload\":{\"fromStatus\":\"%s\",\"toStatus\":\"%s\",\"requestVersion\":%d,"
                + "\"actorIdentityId\":\"%s\"},\"recipients\":[%s]}")
                .formatted(eventId, schemaVersion, type, companyId, requestId, correlationId, occurredAt,
                        fromStatus, toStatus, requestVersion, actorIdentityId, String.join(",", recipientJson));
    }

    record Recipient(UUID identityId, UUID membershipId, List<String> channels) {
    }
}

package com.procureflow.worker.consumer;

import com.procureflow.events.RequestEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict parser for the untrusted schema-v1 envelope. The caller bounds the raw body size before this
 * parser runs; everything here validates shape, required values and enums, and a value is only used
 * after its type was checked. Unknown, missing or wrongly typed fields are rejected instead of
 * ignored, so a forged extra field cannot slip through the canonical comparison. Duplicate object
 * keys (which a lenient reader would resolve to the last value) and any content after the single
 * root value are rejected at parse time.
 *
 * <p>The parser produces a {@link ConsumerEnvelope} claim. It never decides authority: the
 * processor loads the canonical database snapshot and compares this claim against it.
 */
@Component
public class ConsumerEnvelopeParser {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("eventId", "schemaVersion", "type", "companyId",
            "requestId", "correlationId", "occurredAt", "payload", "recipients");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("fromStatus", "toStatus", "requestVersion",
            "actorIdentityId");
    private static final Set<String> RECIPIENT_FIELDS = Set.of("identityId", "membershipId", "channels");

    private final JsonMapper json = JsonMapper.builder()
            // A forged duplicate key must not let a lenient "last one wins" reader override an
            // earlier value, and exactly one root value is accepted before the input must end.
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public Result parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            // Includes malformed JSON, invalid UTF-8 and stream-constraint violations.
            return Result.invalid();
        }
        if (!fieldsExactly(root, TOP_LEVEL_FIELDS)) {
            return Result.invalid();
        }
        UUID eventId = uuid(root.get("eventId"));
        Integer schemaVersion = integer(root.get("schemaVersion"));
        String type = string(root.get("type"));
        UUID companyId = uuid(root.get("companyId"));
        UUID requestId = uuid(root.get("requestId"));
        UUID correlationId = uuid(root.get("correlationId"));
        Instant occurredAt = instant(root.get("occurredAt"));
        if (eventId == null || schemaVersion == null || type == null || companyId == null || requestId == null
                || correlationId == null || occurredAt == null) {
            return Result.invalid();
        }
        JsonNode payload = root.get("payload");
        if (!fieldsExactly(payload, PAYLOAD_FIELDS)) {
            return Result.invalid();
        }
        String fromStatus = string(payload.get("fromStatus"));
        String toStatus = string(payload.get("toStatus"));
        Long requestVersion = longValue(payload.get("requestVersion"));
        UUID actorIdentityId = uuid(payload.get("actorIdentityId"));
        if (fromStatus == null || toStatus == null || requestVersion == null || actorIdentityId == null) {
            return Result.invalid();
        }
        JsonNode recipientsNode = root.get("recipients");
        if (recipientsNode == null || !recipientsNode.isArray()) {
            return Result.invalid();
        }
        List<RequestEvent.Recipient> recipients = new ArrayList<>(recipientsNode.size());
        Set<UUID> recipientIdentities = new HashSet<>();
        for (JsonNode recipientNode : recipientsNode) {
            if (!fieldsExactly(recipientNode, RECIPIENT_FIELDS)) {
                return Result.invalid();
            }
            UUID identityId = uuid(recipientNode.get("identityId"));
            UUID membershipId = uuid(recipientNode.get("membershipId"));
            JsonNode channelsNode = recipientNode.get("channels");
            if (identityId == null || membershipId == null || channelsNode == null || !channelsNode.isArray()
                    || channelsNode.size() == 0 || !recipientIdentities.add(identityId)) {
                return Result.invalid();
            }
            List<RequestEvent.Channel> channels = new ArrayList<>(channelsNode.size());
            Set<RequestEvent.Channel> seen = EnumSet.noneOf(RequestEvent.Channel.class);
            for (JsonNode channelNode : channelsNode) {
                String channelName = string(channelNode);
                if (channelName == null) {
                    return Result.invalid();
                }
                RequestEvent.Channel channel;
                try {
                    channel = RequestEvent.Channel.valueOf(channelName);
                } catch (IllegalArgumentException e) {
                    return Result.invalid();
                }
                if (!seen.add(channel)) {
                    return Result.invalid();
                }
                channels.add(channel);
            }
            recipients.add(new RequestEvent.Recipient(identityId, membershipId, channels));
        }
        return Result.valid(new ConsumerEnvelope(eventId, schemaVersion, type, companyId, requestId, correlationId,
                occurredAt, new RequestEvent.Transition(fromStatus, toStatus, requestVersion, actorIdentityId),
                recipients));
    }

    private static boolean fieldsExactly(JsonNode node, Set<String> expected) {
        return node != null && node.isObject() && new HashSet<>(node.propertyNames()).equals(expected);
    }

    private static String string(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static UUID uuid(JsonNode node) {
        String value = string(node);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.asInt();
    }

    private static Long longValue(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        return node.asLong();
    }

    private static Instant instant(JsonNode node) {
        String value = string(node);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Parse result: exactly one of {@code envelope} and {@code failure} is present. */
    public record Result(ConsumerEnvelope envelope, ConsumerErrorCode failure) {

        public static Result valid(ConsumerEnvelope envelope) {
            return new Result(envelope, null);
        }

        public static Result invalid() {
            return new Result(null, ConsumerErrorCode.MALFORMED_MESSAGE);
        }

        public boolean valid() {
            return envelope != null;
        }
    }
}

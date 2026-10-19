package com.procureflow.worker.consumer;

import com.procureflow.events.RequestEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.core.MessageProperties;

/**
 * Pure validation rules for the schema-v1 consumer contract: legal event transitions, transport
 * identity checks, semantic comparison against the canonical database snapshot and the independent
 * recipient-table comparison. No database or broker access happens here.
 *
 * <p>The legal transitions mirror the frozen producer: the application's status trigger allows
 * exactly these edges and {@code RequestCommands} emits these event types for them. A small
 * department approval (amount not above EUR 1,000) is final and therefore produces
 * {@code request.approved} directly from the department state.
 */
public final class ConsumerEnvelopeRules {
    private static final Map<String, Set<String>> LEGAL_TRANSITIONS = Map.of(
            "request.submitted", Set.of("DRAFT>AWAITING_DEPARTMENT_APPROVAL"),
            "request.department_approved", Set.of("AWAITING_DEPARTMENT_APPROVAL>AWAITING_FINANCE_APPROVAL"),
            "request.approved", Set.of("AWAITING_DEPARTMENT_APPROVAL>APPROVED",
                    "AWAITING_FINANCE_APPROVAL>APPROVED"),
            "request.rejected", Set.of("AWAITING_DEPARTMENT_APPROVAL>REJECTED",
                    "AWAITING_FINANCE_APPROVAL>REJECTED"),
            "request.cancelled", Set.of("AWAITING_DEPARTMENT_APPROVAL>CANCELLED",
                    "AWAITING_FINANCE_APPROVAL>CANCELLED"));

    private ConsumerEnvelopeRules() {
    }

    public static boolean supportedType(String type) {
        return LEGAL_TRANSITIONS.containsKey(type);
    }

    public static boolean legalTransition(String type, RequestEvent.Transition transition) {
        Set<String> legal = LEGAL_TRANSITIONS.get(type);
        return legal != null && legal.contains(transition.fromStatus() + ">" + transition.toStatus());
    }

    /**
     * The transport envelope must agree with the message's own claim: messageId, type, correlation
     * and the schemaVersion header. Disagreement is a forge signal, not a retryable condition.
     */
    public static boolean transportMatches(MessageProperties properties, ConsumerEnvelope envelope) {
        Integer headerSchemaVersion = schemaVersionHeader(properties);
        return envelope.eventId().toString().equalsIgnoreCase(properties.getMessageId())
                && envelope.type().equals(properties.getType())
                && envelope.correlationId().toString().equalsIgnoreCase(properties.getCorrelationId())
                && headerSchemaVersion != null && headerSchemaVersion == envelope.schemaVersion();
    }

    /**
     * Semantic equality of the received envelope with the canonical one. Both sides are already
     * typed, so UUID case/format, JSON whitespace, object key order and recipient/channel order do
     * not matter; identity, time, transition values, recipient keys and the exact channel set do.
     */
    public static boolean matchesCanonical(ConsumerEnvelope received, ConsumerEnvelope canonical) {
        return received.eventId().equals(canonical.eventId())
                && received.schemaVersion() == canonical.schemaVersion()
                && received.type().equals(canonical.type())
                && received.companyId().equals(canonical.companyId())
                && received.requestId().equals(canonical.requestId())
                && received.correlationId().equals(canonical.correlationId())
                && received.occurredAt().equals(canonical.occurredAt())
                && received.transition().fromStatus().equals(canonical.transition().fromStatus())
                && received.transition().toStatus().equals(canonical.transition().toStatus())
                && received.transition().requestVersion() == canonical.transition().requestVersion()
                && received.transition().actorIdentityId().equals(canonical.transition().actorIdentityId())
                && recipientChannels(received.recipients()).equals(recipientChannels(canonical.recipients()));
    }

    /**
     * Independent comparison of the canonical envelope's recipients against the canonical recipient
     * table snapshot: identical (identity, membership) keys and identical channel sets on both sides.
     */
    public static boolean recipientTableMatches(ConsumerEnvelope canonical,
                                                List<CanonicalEvent.CanonicalRecipient> table) {
        Map<RecipientKey, Set<RequestEvent.Channel>> envelopeRecipients =
                recipientChannels(canonical.recipients());
        if (envelopeRecipients.size() != table.size()) {
            return false;
        }
        for (CanonicalEvent.CanonicalRecipient row : table) {
            Set<RequestEvent.Channel> channels = envelopeRecipients.get(
                    new RecipientKey(row.identityId(), row.membershipId()));
            if (channels == null || !channels.equals(channels(row.inApp(), row.email()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<RecipientKey, Set<RequestEvent.Channel>> recipientChannels(
            List<RequestEvent.Recipient> recipients) {
        Map<RecipientKey, Set<RequestEvent.Channel>> result = new HashMap<>();
        for (RequestEvent.Recipient recipient : recipients) {
            Set<RequestEvent.Channel> previous = result.put(
                    new RecipientKey(recipient.identityId(), recipient.membershipId()),
                    Set.copyOf(recipient.channels()));
            if (previous != null) {
                // The producer contract and the parser both reject duplicate identities; this is a
                // defensive guard so a malformed snapshot can never silently drop a recipient.
                return Map.of();
            }
        }
        return result;
    }

    private static Set<RequestEvent.Channel> channels(boolean inApp, boolean email) {
        Set<RequestEvent.Channel> channels = new HashSet<>();
        if (inApp) {
            channels.add(RequestEvent.Channel.IN_APP);
        }
        if (email) {
            channels.add(RequestEvent.Channel.EMAIL);
        }
        return channels;
    }

    /**
     * The accepted schema-version header must be an integral number in the int range. A fractional
     * value (for example {@code 1.5}), an out-of-range value (for example {@code 4294967297}), a
     * string, a boolean or a missing header is rejected outright, so a producer bug or a forged
     * header cannot be truncated into an accidentally matching version.
     */
    private static Integer schemaVersionHeader(MessageProperties properties) {
        Object header = properties.getHeader("schemaVersion");
        if (header instanceof Byte value) {
            return value.intValue();
        }
        if (header instanceof Short value) {
            return value.intValue();
        }
        if (header instanceof Integer value) {
            return value;
        }
        if (header instanceof Long value && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return value.intValue();
        }
        return null;
    }

    /** Identity/membership pair that identifies a captured recipient. */
    private record RecipientKey(UUID identityId, UUID membershipId) {
    }
}

package com.procureflow.app.requests;

import com.procureflow.app.identity.Actor;
import com.procureflow.events.RequestEvent;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class RequestEvents {
    /**
     * Fan-out bounds. The notification consumer rejects any message body larger than 65,536 bytes
     * as {@code MALFORMED_MESSAGE} and acknowledges it into quarantine, so one canonical event must
     * never carry an unbounded recipient list.
     *
     * <p>A 40-recipient envelope measures roughly 6 KiB with the schema-v1 contract (two UUIDs and
     * two channel names per recipient), leaving more than 10x headroom below the consumer limit.
     * The explicit 16 KiB serialized-body check is a second, future-proof guard: if a contract
     * change makes a 40-recipient body larger, the producer halves the batch before insertion. A
     * single-recipient body that still exceeds the guard fails the command loudly instead of
     * producing a message the consumer would silently quarantine.
     */
    static final int MAX_RECIPIENTS_PER_EVENT = 40;
    static final int MAX_ENVELOPE_BYTES = 16 * 1024;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final JsonMapper json;
    RequestEvents(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.json = json;
    }

    void capture(Actor actor, RequestRecord before, RequestRecord after, String type, Instant now) {
        boolean submitted = type.equals("request.submitted");
        boolean financeNext = type.equals("request.department_approved");
        var previous = before.status();
        var participants = new HashSet<UUID>();
        if (!submitted) {
            participants.add(after.ownerIdentityId());
            if (!financeNext && before.priorApproverIdentityId() != null) participants.add(before.priorApproverIdentityId());
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("company", actor.companyId()); parameters.put("actor", actor.identityId());
        parameters.put("owner", after.ownerIdentityId()); parameters.put("department", after.departmentId());
        parameters.put("participants", participants.isEmpty() ? Set.of(new UUID(0, 0)) : participants);
        parameters.put("hasParticipants", !participants.isEmpty());
        parameters.put("departmentStep", submitted || !financeNext && previous == RequestStatus.AWAITING_DEPARTMENT_APPROVAL);
        parameters.put("financeStep", financeNext || previous == RequestStatus.AWAITING_FINANCE_APPROVAL);
        parameters.put("prior", after.priorApproverIdentityId() == null ? new UUID(0, 0) : after.priorApproverIdentityId());
        var recipients = named.query("""
                SELECT m.id, m.identity_id FROM membership m
                WHERE m.company_id = :company AND m.active AND m.identity_id <> :actor AND (
                (:hasParticipants AND m.identity_id IN (:participants)) OR
                (:departmentStep AND m.identity_id <> :owner AND EXISTS (
                    SELECT 1 FROM membership_role r JOIN manager_department d
                    ON d.company_id = r.company_id AND d.membership_id = r.membership_id
                    WHERE r.company_id = m.company_id AND r.membership_id = m.id AND r.role = 'DEPARTMENT_MANAGER'
                    AND d.department_id = :department)) OR
                (:financeStep AND m.identity_id <> :owner AND m.identity_id <> :prior AND EXISTS (
                    SELECT 1 FROM membership_role r WHERE r.company_id = m.company_id AND r.membership_id = m.id
                    AND r.role = 'FINANCE_APPROVER'))) ORDER BY m.identity_id
                """, parameters, (rs, index) -> new RequestEvent.Recipient(rs.getObject("identity_id", UUID.class),
                rs.getObject("id", UUID.class), List.of(RequestEvent.Channel.IN_APP, RequestEvent.Channel.EMAIL)));
        var transition = new RequestEvent.Transition(previous.name(), after.status().name(), after.version(),
                actor.identityId());
        var eventFactory = (java.util.function.Function<List<RequestEvent.Recipient>, RequestEvent>) batch ->
                new RequestEvent(UUID.randomUUID(), 1, type, actor.companyId(), after.id(),
                        after.correlationId(), now, transition, batch);
        // Recipients stay partitioned in identity order: every identity appears in exactly one
        // canonical event for this business transition, while correlation, occurredAt and the
        // transition snapshot are repeated unchanged on each batch. History and audit are recorded
        // once by the caller and are not split here. An empty recipient set still yields one event.
        //
        // The size guard measures compact Jackson JSON as a conservative proxy; the relay publishes
        // the database's jsonb::text, which can differ slightly. RequestEventFanoutIT measures the
        // authoritative stored envelope::text bytes against the bound.
        int offset = 0;
        do {
            int start = offset;
            int candidateSize = Math.min(MAX_RECIPIENTS_PER_EVENT, recipients.size() - start);
            int batchSize = boundedBatchSize(candidateSize, MAX_ENVELOPE_BYTES,
                    size -> json.writeValueAsString(eventFactory.apply(
                            recipients.subList(start, start + size))).getBytes(StandardCharsets.UTF_8).length);
            var batch = recipients.subList(start, start + batchSize);
            var event = eventFactory.apply(batch);
            insert(event, json.writeValueAsString(event), batch);
            offset += batchSize;
        } while (offset < recipients.size());
    }

    /**
     * Chooses the largest prefix size at most {@code candidateSize} whose serialized body fits
     * {@code maxEnvelopeBytes}, halving on overflow. A single recipient that still exceeds the bound
     * fails the command loudly so a valid business transition can never become an oversized message
     * that the consumer would quarantine. Package-private for the focused guard unit test.
     */
    static int boundedBatchSize(int candidateSize, int maxEnvelopeBytes,
                                java.util.function.ToIntFunction<Integer> encodedBytes) {
        int size = candidateSize;
        while (true) {
            if (encodedBytes.applyAsInt(size) <= maxEnvelopeBytes) {
                return size;
            }
            if (size <= 1) {
                throw new IllegalStateException("A single-recipient notification envelope exceeds the "
                        + maxEnvelopeBytes + "-byte producer bound");
            }
            size = (size + 1) / 2;
        }
    }

    private void insert(RequestEvent event, String envelope, List<RequestEvent.Recipient> recipients) {
        jdbc.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, occurred_at, envelope)
                VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, event.eventId(), event.companyId(), event.requestId(), event.type(), event.correlationId(),
                Timestamp.from(event.occurredAt()), envelope);
        for (var recipient : recipients) jdbc.update("""
                INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)
                """, event.companyId(), event.eventId(), recipient.identityId(), recipient.membershipId());
    }
}

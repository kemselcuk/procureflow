package com.procureflow.app.requests;

import com.procureflow.app.identity.Actor;
import com.procureflow.events.RequestEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class RequestEvents {
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
        var event = new RequestEvent(UUID.randomUUID(), 1, type, actor.companyId(), after.id(), after.correlationId(), now,
                new RequestEvent.Transition(previous.name(), after.status().name(), after.version(), actor.identityId()), recipients);
        jdbc.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, occurred_at, envelope)
                VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, event.eventId(), event.companyId(), event.requestId(), event.type(), event.correlationId(),
                Timestamp.from(now), json.writeValueAsString(event));
        for (var recipient : recipients) jdbc.update("""
                INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)
                """, event.companyId(), event.eventId(), recipient.identityId(), recipient.membershipId());
    }
}

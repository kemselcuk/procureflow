package com.procureflow.app.audit;

import com.procureflow.app.identity.Actor;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditWriter {
    private final JdbcTemplate jdbc;
    public AuditWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(Actor actor, String action, UUID entityId) {
        jdbc.update("INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), actor.companyId(), actor.identityId(), actor.membershipId(), action, entityId);
    }

    public void record(Actor actor, String action, UUID entityId, UUID correlationId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.companyId(), actor.identityId(), actor.membershipId(), action, entityId,
                correlationId, Timestamp.from(occurredAt));
    }
}

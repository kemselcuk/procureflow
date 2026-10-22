package com.procureflow.app.support;

import com.procureflow.app.identity.DemoData;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deterministic, test-only fixture builders. Every identifier is derived from a stable name so
 * repeated runs produce identical rows; the helpers insert through the database-owner login only
 * and never through an application endpoint.
 */
public final class QueryFixtures {
    private QueryFixtures() {}

    public static final String FIXTURE_ISSUER = "https://fixture.invalid/realms/procureflow";

    /** Adds {@code count} active memberships with deterministic identities and roles. */
    public static List<UUID> seedMembers(JdbcTemplate jdbc, UUID company, UUID department, UUID managedDepartment, int count) {
        var memberships = new ArrayList<UUID>(count);
        for (var index = 0; index < count; index++) {
            var identity = DemoData.id(memberKey(company, index) + ":identity");
            var membership = DemoData.id(memberKey(company, index) + ":membership");
            var manager = index % 5 == 0;
            jdbc.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, ?, ?)",
                    identity, FIXTURE_ISSUER, "perf-member-" + company + "-" + index);
            jdbc.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) VALUES (?, ?, ?, ?, true)",
                    membership, company, identity, manager ? managedDepartment : department);
            jdbc.update("INSERT INTO membership_role VALUES (?, ?, ?)", company, membership, manager ? "DEPARTMENT_MANAGER" : "EMPLOYEE");
            if (manager) jdbc.update("INSERT INTO manager_department VALUES (?, ?, ?)", company, membership, managedDepartment);
            memberships.add(membership);
        }
        return memberships;
    }

    /** Adds one DRAFT request plus its version-0 history row. */
    public static UUID seedDraftRequest(JdbcTemplate jdbc, UUID company, UUID identity, UUID membership, UUID department, Instant createdAt) {
        var request = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                    title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request, company, identity, membership, department, "Fixture request", "Deterministic fixture",
                new BigDecimal("10.00"), Timestamp.from(createdAt), Timestamp.from(createdAt), correlation);
        jdbc.update("""
                INSERT INTO request_history (id, company_id, request_id, request_version, action, to_status,
                    actor_identity_id, actor_membership_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, 0, 'request.created', 'DRAFT', ?, ?, ?, ?)
                """, UUID.randomUUID(), company, request, identity, membership, correlation, Timestamp.from(createdAt));
        return request;
    }

    /** Adds one outbox event for an existing request. */
    public static UUID seedOutboxEvent(JdbcTemplate jdbc, UUID company, UUID request, String eventType, Instant occurredAt) {
        var event = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, occurred_at, envelope)
                VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, event, company, request, eventType, correlation, Timestamp.from(occurredAt),
                envelope(company, request, event, eventType, correlation));
        return event;
    }

    public static void seedRecipient(JdbcTemplate jdbc, UUID company, UUID event, UUID identity, UUID membership) {
        jdbc.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                company, event, identity, membership);
    }

    public static UUID seedDelivery(JdbcTemplate jdbc, UUID company, UUID event, UUID identity, UUID membership,
                                    String channel, String outcome) {
        var delivery = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, channel, outcome, reason_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, delivery, company, event, identity, membership, channel, outcome,
                "SKIPPED".equals(outcome) ? "recipient_inactive" : null);
        return delivery;
    }

    public static UUID seedInAppNotification(JdbcTemplate jdbc, UUID delivery, UUID company, UUID event, UUID identity,
                                             UUID membership, Instant createdAt) {
        var notification = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO in_app_notification (id, delivery_id, company_id, event_id, identity_id, membership_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, notification, delivery, company, event, identity, membership, Timestamp.from(createdAt));
        return notification;
    }

    /** One complete own notification for an identity; returns the notification id. */
    public static UUID seedNotification(JdbcTemplate jdbc, UUID company, UUID identity, UUID membership, UUID department, Instant createdAt) {
        var request = seedDraftRequest(jdbc, company, identity, membership, department, createdAt);
        var event = seedOutboxEvent(jdbc, company, request, "request.submitted", createdAt);
        seedRecipient(jdbc, company, event, identity, membership);
        var delivery = seedDelivery(jdbc, company, event, identity, membership, "IN_APP", "MATERIALIZED");
        return seedInAppNotification(jdbc, delivery, company, event, identity, membership, createdAt);
    }

    public static void seedAuditEntry(JdbcTemplate jdbc, UUID company, UUID identity, UUID membership, String action,
                                      Instant occurredAt) {
        jdbc.update("""
                INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), company, identity, membership, action, UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(occurredAt));
    }

    /**
     * Bulk outbox/delivery population for plan evidence. Seeds {@code members} real memberships
     * first, then one event per iteration fans out to every member with one IN_APP delivery whose
     * outcome is SKIPPED every {@code skippedEvery} recipient, so per-company/per-outcome counts
     * see both outcomes on a realistic multi-company table.
     */
    public static void populateDeliveryBacklog(JdbcTemplate jdbc, UUID company, UUID department, int members, int events,
                                               int skippedEvery) {
        seedMembers(jdbc, company, department, department, members);
        var base = Instant.parse("2026-01-01T00:00:00Z");
        var requestBatch = new ArrayList<Object[]>();
        var historyBatch = new ArrayList<Object[]>();
        var eventBatch = new ArrayList<Object[]>();
        var recipientBatch = new ArrayList<Object[]>();
        var deliveryBatch = new ArrayList<Object[]>();
        var requesterIdentity = DemoData.id(memberKey(company, 0) + ":identity");
        var requesterMembership = DemoData.id(memberKey(company, 0) + ":membership");
        for (var eventIndex = 0; eventIndex < events; eventIndex++) {
            var request = UUID.randomUUID();
            var correlation = UUID.randomUUID();
            var event = UUID.randomUUID();
            var occurredAt = Timestamp.from(base.plusSeconds(eventIndex));
            requestBatch.add(new Object[] {request, company, requesterIdentity, requesterMembership, department, "Backlog request",
                    "Deterministic backlog fixture", new BigDecimal("10.00"), occurredAt, occurredAt, correlation});
            historyBatch.add(new Object[] {UUID.randomUUID(), company, request, requesterIdentity, requesterMembership, correlation, occurredAt});
            eventBatch.add(new Object[] {event, company, request, "request.submitted", correlation, occurredAt,
                    envelope(company, request, event, "request.submitted", correlation)});
            for (var recipientIndex = 0; recipientIndex < members; recipientIndex++) {
                var recipientIdentity = DemoData.id(memberKey(company, recipientIndex) + ":identity");
                var recipientMembership = DemoData.id(memberKey(company, recipientIndex) + ":membership");
                var outcome = recipientIndex % skippedEvery == 0 ? "SKIPPED" : "MATERIALIZED";
                recipientBatch.add(new Object[] {company, event, recipientIdentity, recipientMembership});
                deliveryBatch.add(new Object[] {UUID.randomUUID(), company, event, recipientIdentity, recipientMembership,
                        "IN_APP", outcome, "SKIPPED".equals(outcome) ? "recipient_inactive" : null});
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                    title, justification, amount, created_at, updated_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestBatch);
        jdbc.batchUpdate("""
                INSERT INTO request_history (id, company_id, request_id, request_version, action, to_status,
                    actor_identity_id, actor_membership_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, 0, 'request.created', 'DRAFT', ?, ?, ?, ?)
                """, historyBatch);
        jdbc.batchUpdate("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, occurred_at, envelope)
                VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, eventBatch);
        jdbc.batchUpdate("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                recipientBatch);
        jdbc.batchUpdate("""
                INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, channel, outcome, reason_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, deliveryBatch);
    }

    /** Company-scoped stable fixture key so two fixture companies never collide on identity ids. */
    public static String memberKey(UUID company, int index) {
        return "perf:" + company + ":member:" + index;
    }

    private static String envelope(UUID company, UUID request, UUID event, String type, UUID correlation) {
        return """
                {"eventId":"%s","companyId":"%s","requestId":"%s","type":"%s","correlationId":"%s","schemaVersion":1,
                 "transition":{"fromStatus":"DRAFT","toStatus":"DRAFT"}}
                """.formatted(event, company, request, type, correlation);
    }
}

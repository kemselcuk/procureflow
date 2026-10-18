package com.procureflow.app.requests;

import com.procureflow.app.audit.AuditWriter;
import com.procureflow.app.budgets.BudgetCharger;
import com.procureflow.app.identity.Actor;
import com.procureflow.app.identity.ActorAccess;
import com.procureflow.app.identity.Role;
import com.procureflow.app.shared.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestCommands {
    private final ActorAccess access;
    private final RequestRepository requests;
    private final CommandResults results;
    private final RequestEvents events;
    private final AuditWriter audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BudgetCharger budgets;
    RequestCommands(ActorAccess access, RequestRepository requests, CommandResults results, RequestEvents events,
                    AuditWriter audit, JdbcTemplate jdbc, Clock clock, BudgetCharger budgets) {
        this.access = access; this.requests = requests; this.results = results; this.events = events;
        this.audit = audit; this.jdbc = jdbc; this.clock = clock; this.budgets = budgets;
    }

    @Transactional
    public RequestData.Result create(UUID key, RequestData.Create input) {
        var actor = access.command(false); employee(actor);
        var content = RequestData.Content.of(input.title(), input.justification(), input.amount());
        var now = commandTime();
        var replay = results.claim(actor, key, "CREATE", content, now);
        if (replay != null) return replay;
        var id = UUID.randomUUID(); var correlation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                title, justification, amount, created_at, updated_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor.companyId(), actor.identityId(), actor.membershipId(), actor.departmentId(), content.title(),
                content.justification(), content.amount(), Timestamp.from(now), Timestamp.from(now), correlation);
        var created = requests.find(actor.companyId(), id, false);
        record(actor, null, created, "request.created", null, now);
        return results.complete(actor, key, 201, created.view());
    }

    @Transactional
    public RequestData.Result edit(UUID id, RequestData.Edit input) {
        var actor = access.command(false); employee(actor);
        var before = requests.find(actor.companyId(), id, true); owner(actor, before);
        version(before, input.version()); requireState(before, RequestStatus.DRAFT);
        var content = RequestData.Content.of(input.title(), input.justification(), input.amount());
        var now = commandTime(); var correlation = UUID.randomUUID();
        jdbc.update("""
                UPDATE purchase_request SET department_id = ?, title = ?, justification = ?, amount = ?,
                version = version + 1, updated_at = ?, correlation_id = ? WHERE company_id = ? AND id = ?
                """, actor.departmentId(), content.title(), content.justification(), content.amount(), Timestamp.from(now),
                correlation, actor.companyId(), id);
        var after = requests.find(actor.companyId(), id, false);
        record(actor, before, after, "request.edited", null, now);
        return new RequestData.Result(200, after.view());
    }

    @Transactional
    public RequestData.Result submit(UUID id, UUID key, RequestData.Version input) {
        var actor = access.command(false); employee(actor);
        // Scoped read/ownership precedes key lookup; it acquires no request row lock before the key claim.
        owner(actor, requests.find(actor.companyId(), id, false));
        var now = commandTime();
        var replay = results.claim(actor, key, "SUBMIT", List.of(id, input.version()), now);
        var before = requests.find(actor.companyId(), id, true); owner(actor, before);
        if (replay != null) return replay;
        version(before, input.version()); requireState(before, RequestStatus.DRAFT);
        if (!before.departmentId().equals(actor.departmentId())) throw ApiException.conflict("request_department_changed");
        return results.complete(actor, key, 200,
                transition(actor, before, RequestStatus.AWAITING_DEPARTMENT_APPROVAL, "request.submitted", null, now).view());
    }

    @Transactional
    public RequestData.Result cancel(UUID id, RequestData.Version input) {
        var actor = access.command(false);
        var before = requests.find(actor.companyId(), id, true); owner(actor, before);
        version(before, input.version());
        if (!before.status().pending()) throw ApiException.conflict("invalid_request_state");
        return new RequestData.Result(200, transition(actor, before, RequestStatus.CANCELLED, "request.cancelled", null, commandTime()).view());
    }

    @Transactional
    public RequestData.Result reject(UUID id, RequestData.Reject input) {
        var actor = access.command(false);
        var before = requests.find(actor.companyId(), id, true); requests.requireRead(actor, before);
        if (before.ownerIdentityId().equals(actor.identityId()) || before.status() == RequestStatus.AWAITING_FINANCE_APPROVAL
                && actor.identityId().equals(before.priorApproverIdentityId()))
            throw ApiException.forbidden();
        if (!before.status().pending()) throw ApiException.conflict("invalid_request_state");
        boolean permitted = before.status() == RequestStatus.AWAITING_DEPARTMENT_APPROVAL
                ? actor.has(Role.DEPARTMENT_MANAGER) && actor.managedDepartmentIds().contains(before.departmentId())
                : actor.has(Role.FINANCE_APPROVER);
        if (!permitted) throw ApiException.forbidden();
        version(before, input.version());
        if (input.reason().indexOf('\0') >= 0) throw ApiException.invalid("invalid_rejection_reason");
        return new RequestData.Result(200, transition(actor, before, RequestStatus.REJECTED, "request.rejected", input.reason().strip(), commandTime()).view());
    }

    @Transactional
    public RequestData.Result approve(UUID id, RequestData.Version input) {
        var actor = access.command(false);
        var before = requests.find(actor.companyId(), id, true); requests.requireRead(actor, before);
        if (before.ownerIdentityId().equals(actor.identityId())) throw ApiException.forbidden();
        version(before, input.version());
        if (!before.status().pending()) throw ApiException.conflict("invalid_request_state");
        boolean department = before.status() == RequestStatus.AWAITING_DEPARTMENT_APPROVAL;
        if (department ? !actor.has(Role.DEPARTMENT_MANAGER) || !actor.managedDepartmentIds().contains(before.departmentId())
                : !actor.has(Role.FINANCE_APPROVER) || actor.identityId().equals(before.priorApproverIdentityId()))
            throw ApiException.forbidden();
        var now = commandTime(); var correlation = UUID.randomUUID();
        boolean finalApproval = !department || before.amount().compareTo(new java.math.BigDecimal("1000.00")) <= 0;
        var next = finalApproval ? RequestStatus.APPROVED : RequestStatus.AWAITING_FINANCE_APPROVAL;
        var action = finalApproval ? "request.approved" : "request.department_approved";
        if (finalApproval) budgets.charge(actor.companyId(), before.departmentId(), id, before.amount(), before.version() + 1, now, correlation);
        jdbc.update("""
                UPDATE purchase_request SET status = ?, version = version + 1, updated_at = ?, correlation_id = ?,
                department_approver_identity_id = ?, department_approver_membership_id = ?, approved_at = ?
                WHERE company_id = ? AND id = ?
                """, next.name(), Timestamp.from(now), correlation,
                department ? actor.identityId() : before.priorApproverIdentityId(),
                department ? actor.membershipId() : before.priorApproverMembershipId(),
                finalApproval ? Timestamp.from(now) : null, actor.companyId(), id);
        var after = requests.find(actor.companyId(), id, false);
        jdbc.update("""
                INSERT INTO approval_step (company_id, request_id, step, request_version, actor_identity_id,
                actor_membership_id, occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, actor.companyId(), id, department ? "DEPARTMENT" : "FINANCE", after.version(),
                actor.identityId(), actor.membershipId(), Timestamp.from(now), correlation);
        record(actor, before, after, action, null, now);
        events.capture(actor, before, after, action, now);
        return new RequestData.Result(200, after.view());
    }

    private RequestRecord transition(Actor actor, RequestRecord before, RequestStatus next, String action, String reason, Instant now) {
        var correlation = UUID.randomUUID();
        jdbc.update("""
                UPDATE purchase_request SET status = ?, version = version + 1, updated_at = ?, correlation_id = ?,
                submitted_at = COALESCE(submitted_at, ?) WHERE company_id = ? AND id = ?
                """, next.name(), Timestamp.from(now), correlation, Timestamp.from(now), actor.companyId(), before.id());
        var after = requests.find(actor.companyId(), before.id(), false);
        record(actor, before, after, action, reason, now);
        events.capture(actor, before, after, action, now);
        return after;
    }

    private void record(Actor actor, RequestRecord before, RequestRecord after, String action, String reason, Instant now) {
        jdbc.update("""
                INSERT INTO request_history (id, company_id, request_id, request_version, action, from_status, to_status,
                actor_identity_id, actor_membership_id, reason, correlation_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.companyId(), after.id(), after.version(), action, before == null ? null : before.status().name(),
                after.status().name(), actor.identityId(), actor.membershipId(), reason, after.correlationId(), Timestamp.from(now));
        audit.record(actor, action, after.id(), after.correlationId(), now);
    }

    private static void employee(Actor actor) { if (!actor.has(Role.EMPLOYEE)) throw ApiException.forbidden(); }
    private Instant commandTime() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private static void owner(Actor actor, RequestRecord request) {
        if (!request.ownerIdentityId().equals(actor.identityId())) throw ApiException.forbidden();
    }
    private static void version(RequestRecord request, long expected) {
        if (request.version() != expected) throw ApiException.conflict("stale_request");
    }
    private static void requireState(RequestRecord request, RequestStatus state) {
        if (request.status() != state) throw ApiException.conflict("invalid_request_state");
    }
}

package com.procureflow.app.requests;

import com.procureflow.app.config.PageBounds;
import com.procureflow.app.identity.Actor;
import com.procureflow.app.identity.Role;
import com.procureflow.app.shared.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RequestRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    RequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); }

    RequestRecord find(UUID company, UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM purchase_request WHERE company_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> row(rs), company, id);
        if (rows.isEmpty()) throw ApiException.notFound();
        return rows.getFirst();
    }

    void requireRead(Actor actor, RequestRecord request) {
        if (request.ownerIdentityId().equals(actor.identityId())) return;
        boolean finance = actor.has(Role.FINANCE_APPROVER) && request.amount().compareTo(new java.math.BigDecimal("1000.00")) > 0
                && request.priorApproverIdentityId() != null;
        boolean manager = actor.has(Role.DEPARTMENT_MANAGER) && actor.managedDepartmentIds().contains(request.departmentId());
        if (request.status() != RequestStatus.DRAFT && (finance || manager)) return;
        throw ApiException.forbidden();
    }

    /**
     * One statement evaluates the scoped page and its total from the same PostgreSQL snapshot, so
     * the returned {@code total} always matches the page contents even while rows are inserted or
     * updated concurrently. The scope predicates and ordering are unchanged from the previous
     * count-plus-page implementation.
     */
    RequestData.Page page(Actor actor, String view, RequestStatus status, int page, int size) {
        long offset = PageBounds.offset(page, size, "invalid_page");
        if (!List.of("mine", "inbox").contains(view)) throw ApiException.invalid("invalid_view");
        if (view.equals("inbox") && !actor.has(Role.DEPARTMENT_MANAGER) && !actor.has(Role.FINANCE_APPROVER))
            throw ApiException.forbidden();
        var parameters = new HashMap<String, Object>();
        parameters.put("company", actor.companyId()); parameters.put("actor", actor.identityId());
        parameters.put("manager", actor.has(Role.DEPARTMENT_MANAGER)); parameters.put("finance", actor.has(Role.FINANCE_APPROVER));
        parameters.put("departments", actor.managedDepartmentIds().isEmpty() ? Set.of(new UUID(0, 0)) : actor.managedDepartmentIds());
        parameters.put("size", size); parameters.put("offset", offset);
        var predicate = "company_id = :company AND " + (view.equals("mine") ? "requester_identity_id = :actor" : """
                requester_identity_id <> :actor AND (
                (:manager AND status = 'AWAITING_DEPARTMENT_APPROVAL' AND department_id IN (:departments)) OR
                (:finance AND status = 'AWAITING_FINANCE_APPROVAL' AND department_approver_identity_id <> :actor))
                """);
        if (status != null) { predicate += " AND status = :status"; parameters.put("status", status.name()); }
        var sql = """
                WITH scoped AS (
                    SELECT id, department_id, title, amount, status, version, created_at
                    FROM purchase_request WHERE %s
                    ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset
                ), total AS (
                    SELECT count(*) AS total FROM purchase_request WHERE %s
                )
                SELECT t.total, s.id, s.department_id, s.title, s.amount, s.status, s.version, s.created_at
                FROM total t LEFT JOIN scoped s ON true
                ORDER BY s.created_at DESC NULLS LAST, s.id DESC NULLS LAST
                """.formatted(predicate, predicate);
        return named.query(sql, parameters, resultSet -> {
            long total = 0;
            var items = new ArrayList<RequestData.Summary>();
            while (resultSet.next()) {
                total = resultSet.getLong("total");
                var id = resultSet.getObject("id", UUID.class);
                if (id == null) continue;
                items.add(new RequestData.Summary(id, resultSet.getObject("department_id", UUID.class),
                        resultSet.getString("title"), resultSet.getBigDecimal("amount"),
                        RequestStatus.valueOf(resultSet.getString("status")), resultSet.getLong("version"),
                        resultSet.getTimestamp("created_at").toInstant()));
            }
            return new RequestData.Page(page, size, total, List.copyOf(items));
        });
    }

    List<RequestData.History> history(UUID company, UUID id) {
        return jdbc.query("SELECT * FROM request_history WHERE company_id = ? AND request_id = ? ORDER BY request_version",
                (rs, index) -> new RequestData.History(rs.getObject("id", UUID.class), rs.getLong("request_version"),
                        rs.getString("action"), rs.getString("from_status"), rs.getString("to_status"),
                        rs.getObject("actor_identity_id", UUID.class), rs.getString("reason"),
                        rs.getObject("correlation_id", UUID.class), rs.getTimestamp("occurred_at").toInstant()), company, id);
    }

    private static RequestRecord row(ResultSet rs) throws SQLException {
        return new RequestRecord(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("requester_identity_id", UUID.class), rs.getObject("requester_membership_id", UUID.class),
                rs.getObject("department_id", UUID.class), rs.getString("title"), rs.getString("justification"),
                rs.getBigDecimal("amount"), RequestStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                rs.getObject("department_approver_identity_id", UUID.class), rs.getObject("department_approver_membership_id", UUID.class),
                rs.getObject("correlation_id", UUID.class),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                rs.getDate("budget_month") == null ? null : rs.getDate("budget_month").toLocalDate());
    }
}

package com.procureflow.app.audit;

import com.procureflow.app.config.PageBounds;
import com.procureflow.app.identity.ActorAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditQueries {
    private final ActorAccess access;
    private final JdbcTemplate jdbc;

    AuditQueries(ActorAccess access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    /**
     * Read-only, current-company audit metadata. Authentication and the current active membership
     * are re-read on every request, and the company filter comes exclusively from that actor; a
     * token role, caller header or query parameter never selects the company.
     */
    public AuditData.Page page(int page, int size) {
        var actor = access.current();
        actor.requireAdmin();
        // Same bounded page policy as the request/notification list APIs: an offset beyond the
        // shared cap is a client error instead of a deep-offset scan.
        long offset = PageBounds.offset(page, size, "invalid_request");

        // One statement evaluates the count and the requested page from the same PostgreSQL
        // snapshot. That keeps the response internally consistent without a repeatable-read
        // transaction or any new business/company lock.
        return jdbc.query("""
                WITH scoped AS (
                    SELECT id, action, entity_id, actor_identity_id, actor_membership_id, correlation_id, occurred_at
                    FROM audit_entry
                    WHERE company_id = ?
                    ORDER BY occurred_at DESC, id DESC
                    LIMIT ? OFFSET ?
                ), total AS (
                    SELECT count(*) AS total FROM audit_entry WHERE company_id = ?
                )
                SELECT t.total, s.id, s.action, s.entity_id, s.actor_identity_id, s.actor_membership_id,
                       s.correlation_id, s.occurred_at
                FROM total t LEFT JOIN scoped s ON true
                ORDER BY s.occurred_at DESC NULLS LAST, s.id DESC NULLS LAST
                """, resultSet -> {
            long total = 0;
            var items = new ArrayList<AuditData.Entry>();
            while (resultSet.next()) {
                total = resultSet.getLong("total");
                var id = resultSet.getObject("id", UUID.class);
                if (id == null) continue;
                items.add(new AuditData.Entry(id, resultSet.getString("action"),
                        resultSet.getObject("entity_id", UUID.class),
                        resultSet.getObject("actor_identity_id", UUID.class),
                        resultSet.getObject("actor_membership_id", UUID.class),
                        resultSet.getObject("correlation_id", UUID.class),
                        resultSet.getTimestamp("occurred_at").toInstant()));
            }
            return new AuditData.Page(page, size, total, List.copyOf(items));
        }, actor.companyId(), size, offset, actor.companyId());
    }
}

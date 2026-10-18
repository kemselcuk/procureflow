package com.procureflow.app.notifications;

import com.procureflow.app.identity.Actor;
import com.procureflow.app.shared.ApiException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    NotificationData.Page page(Actor actor, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw ApiException.invalid("invalid_page");
        var total = jdbc.queryForObject("""
                SELECT count(*) FROM in_app_notification WHERE company_id = ? AND identity_id = ?
                """, Long.class, actor.companyId(), actor.identityId());
        // Metadata comes from the same-company outbox event; the notification list never reads purchase content.
        var items = jdbc.query("""
                SELECT n.id, n.event_id, e.event_type, e.request_id, e.correlation_id, e.occurred_at,
                       n.created_at, n.read_at
                FROM in_app_notification n
                JOIN outbox_event e ON e.company_id = n.company_id AND e.id = n.event_id
                WHERE n.company_id = ? AND n.identity_id = ?
                ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?
                """, (rs, index) -> new NotificationData.Item(rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getObject("request_id", UUID.class), rs.getObject("correlation_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()),
                actor.companyId(), actor.identityId(), size, (long) page * size);
        return new NotificationData.Page(page, size, Objects.requireNonNull(total), items);
    }

    /**
     * Company/identity-scoped read marker. COALESCE keeps the first committed read time, so a
     * concurrent or repeated retry returns the same value instead of overwriting it.
     */
    NotificationData.Read markRead(Actor actor, UUID id) {
        var rows = jdbc.query("""
                UPDATE in_app_notification SET read_at = COALESCE(read_at, now())
                WHERE company_id = ? AND identity_id = ? AND id = ?
                RETURNING id, read_at
                """, (rs, index) -> new NotificationData.Read(rs.getObject("id", UUID.class),
                rs.getTimestamp("read_at").toInstant()), actor.companyId(), actor.identityId(), id);
        if (rows.isEmpty()) throw ApiException.notFound();
        return rows.getFirst();
    }

    /**
     * One statement evaluates all counts from the same snapshot. notification_diagnostic is
     * intentionally not queried: the app role has no grant on it and raw diagnostics stay internal.
     */
    NotificationData.Backlog backlog(UUID company) {
        return named.queryForObject("""
                SELECT
                    (SELECT count(*) FROM outbox_dispatch d WHERE d.company_id = :company AND d.state = 'PENDING') AS dispatch_pending,
                    (SELECT count(*) FROM outbox_dispatch d WHERE d.company_id = :company AND d.state = 'LEASED') AS dispatch_leased,
                    (SELECT count(*) FROM outbox_dispatch d WHERE d.company_id = :company AND d.state = 'PUBLISHED') AS dispatch_published,
                    (SELECT count(*) FROM outbox_dispatch d WHERE d.company_id = :company AND d.state = 'QUARANTINED') AS dispatch_quarantined,
                    (SELECT count(*) FROM email_job j WHERE j.company_id = :company AND j.state = 'PENDING') AS email_pending,
                    (SELECT count(*) FROM email_job j WHERE j.company_id = :company AND j.state = 'LEASED') AS email_leased,
                    (SELECT count(*) FROM email_job j WHERE j.company_id = :company AND j.state = 'SENT') AS email_sent,
                    (SELECT count(*) FROM email_job j WHERE j.company_id = :company AND j.state = 'QUARANTINED') AS email_quarantined,
                    (SELECT count(*) FROM email_job j WHERE j.company_id = :company AND j.state = 'SKIPPED') AS email_skipped,
                    (SELECT count(*) FROM notification_delivery v WHERE v.company_id = :company AND v.outcome = 'MATERIALIZED') AS deliveries_materialized,
                    (SELECT count(*) FROM notification_delivery v WHERE v.company_id = :company AND v.outcome = 'SKIPPED') AS deliveries_skipped,
                    (SELECT count(*) FROM notification_inbox i WHERE i.company_id = :company) AS inbox_total
                """, Map.of("company", company), (rs, index) -> new NotificationData.Backlog(company,
                new NotificationData.Dispatch(rs.getLong("dispatch_pending"), rs.getLong("dispatch_leased"),
                        rs.getLong("dispatch_published"), rs.getLong("dispatch_quarantined")),
                new NotificationData.Email(rs.getLong("email_pending"), rs.getLong("email_leased"),
                        rs.getLong("email_sent"), rs.getLong("email_quarantined"), rs.getLong("email_skipped")),
                new NotificationData.Deliveries(rs.getLong("deliveries_materialized"), rs.getLong("deliveries_skipped")),
                rs.getLong("inbox_total")));
    }
}

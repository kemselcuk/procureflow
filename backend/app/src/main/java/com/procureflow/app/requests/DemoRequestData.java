package com.procureflow.app.requests;

import static com.procureflow.app.identity.DemoData.id;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
@Profile("local-demo")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoRequestData implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    DemoRequestData(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var month = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        for (var company : List.of("a", "b")) {
            jdbc.queryForObject("SELECT id FROM company WHERE id = ? FOR SHARE", UUID.class, id(company));
            var administrator = jdbc.queryForList("""
                    SELECT m.id FROM membership m JOIN membership_role r ON r.company_id = m.company_id AND r.membership_id = m.id
                    WHERE m.company_id = ? AND m.id = ? AND m.active AND r.role = 'COMPANY_ADMIN'
                    """, UUID.class, id(company), id(company + ":admin:membership"));
            if (!administrator.isEmpty()) for (var department : List.of("engineering", "operations")) {
                var budget = id(company + ":budget:" + department + ":" + month);
                if (jdbc.update("INSERT INTO monthly_budget (id, company_id, department_id, budget_month, limit_amount) VALUES (?, ?, ?, ?, 5000) ON CONFLICT DO NOTHING",
                        budget, id(company), id(company + ":" + department), Date.valueOf(month)) == 1)
                    jdbc.update("INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at) VALUES (?, ?, ?, ?, 'demo.budget_seeded', ?, ?, ?)",
                            UUID.randomUUID(), id(company), id(company + ":admin:identity"), administrator.getFirst(), budget, UUID.randomUUID(), Timestamp.from(now));
            }
            var departments = jdbc.queryForList("""
                    SELECT m.department_id FROM membership m JOIN membership_role r ON r.company_id = m.company_id AND r.membership_id = m.id
                    WHERE m.company_id = ? AND m.id = ? AND m.active AND r.role = 'EMPLOYEE'
                    """, UUID.class, id(company), id(company + ":employee:membership"));
            if (departments.isEmpty()) continue;
            for (var amount : List.of("999.99", "1000.00", "1000.01", "6000.00")) {
                var request = id(company + ":sample:" + amount); var correlation = UUID.randomUUID();
                if (jdbc.update("""
                        INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                        title, justification, amount, created_at, updated_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """, request, id(company), id(company + ":employee:identity"), id(company + ":employee:membership"), departments.getFirst(),
                        "Demo purchase EUR " + amount, "Fictional draft for threshold and budget checks; no real payment.",
                        new BigDecimal(amount), Timestamp.from(now), Timestamp.from(now), correlation) == 0) continue;
                jdbc.update("""
                        INSERT INTO request_history (id, company_id, request_id, request_version, action, to_status,
                        actor_identity_id, actor_membership_id, correlation_id, occurred_at)
                        VALUES (?, ?, ?, 0, 'request.created', 'DRAFT', ?, ?, ?, ?)
                        """, UUID.randomUUID(), id(company), request, id(company + ":employee:identity"), id(company + ":employee:membership"), correlation, Timestamp.from(now));
                jdbc.update("INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at) VALUES (?, ?, ?, ?, 'request.created', ?, ?, ?)",
                        UUID.randomUUID(), id(company), id(company + ":employee:identity"), id(company + ":employee:membership"), request, correlation, Timestamp.from(now));
            }
        }
    }
}

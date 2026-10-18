package com.procureflow.app.budgets;

import com.procureflow.app.shared.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetCharger {
    private final BudgetRepository budgets;
    private final JdbcTemplate jdbc;
    BudgetCharger(BudgetRepository budgets, JdbcTemplate jdbc) { this.budgets = budgets; this.jdbc = jdbc; }

    /** Caller must already hold the company authorization barrier and the scoped request lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void charge(UUID company, UUID department, UUID request, BigDecimal amount, long version, Instant now, UUID correlation) {
        var month = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        var budget = budgets.lockPeriod(company, department, month).orElseThrow(() -> ApiException.conflict("budget_missing"));
        budget.charge(amount);
        budgets.saveAndFlush(budget);
        jdbc.update("""
                INSERT INTO budget_charge (id, company_id, request_id, department_id, budget_id, budget_month, amount,
                approval_version, occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), company, request, department, budget.view().id(), Date.valueOf(month), amount,
                version, Timestamp.from(now), correlation);
    }
}

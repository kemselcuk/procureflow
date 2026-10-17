package com.procureflow.app.budgets;

import com.procureflow.app.shared.ApiException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "monthly_budget")
public class MonthlyBudget {
    @Id private UUID id;
    @Column(name = "company_id", nullable = false) private UUID companyId;
    @Column(name = "department_id", nullable = false) private UUID departmentId;
    @Column(name = "budget_month", nullable = false) private LocalDate budgetMonth;
    @Column(name = "limit_amount", nullable = false, precision = 14, scale = 2) private BigDecimal limitAmount;
    @Column(name = "used_amount", nullable = false, precision = 14, scale = 2) private BigDecimal usedAmount;
    @Version private Long version;

    protected MonthlyBudget() {}

    MonthlyBudget(UUID company, UUID department, LocalDate month, BigDecimal limit) {
        id = UUID.randomUUID(); companyId = company; departmentId = department; budgetMonth = month;
        limitAmount = limit; usedAmount = BigDecimal.ZERO;
    }

    void changeLimit(BigDecimal limit) {
        if (limit.compareTo(usedAmount) < 0) throw ApiException.conflict("budget_below_usage");
        limitAmount = limit;
    }

    BudgetService.BudgetView view() {
        return new BudgetService.BudgetView(id, departmentId, budgetMonth.toString(), limitAmount, usedAmount,
                limitAmount.subtract(usedAmount), version);
    }
}

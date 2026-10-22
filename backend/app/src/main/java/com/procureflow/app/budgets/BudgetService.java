package com.procureflow.app.budgets;

import com.procureflow.app.audit.AuditWriter;
import com.procureflow.app.identity.ActorAccess;
import com.procureflow.app.identity.DirectoryService;
import com.procureflow.app.identity.Role;
import com.procureflow.app.shared.ApiException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetService {
    private final ActorAccess access;
    private final DirectoryService directory;
    private final BudgetRepository budgets;
    private final AuditWriter audit;

    public BudgetService(ActorAccess access, DirectoryService directory, BudgetRepository budgets, AuditWriter audit) {
        this.access = access; this.directory = directory; this.budgets = budgets; this.audit = audit;
    }

    public record LimitInput(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal limitAmount) {}
    public record BudgetView(UUID id, UUID departmentId, String month, BigDecimal limitAmount, BigDecimal usedAmount,
                             BigDecimal remainingAmount, Long version) {}

    @Transactional(readOnly = true)
    public List<BudgetView> list(String month) {
        var actor = access.current();
        var period = period(month);
        if (actor.has(Role.COMPANY_ADMIN) || actor.has(Role.FINANCE_APPROVER))
            return budgets.companyMonth(actor.companyId(), period).stream().map(MonthlyBudget::view).toList();
        if (!actor.has(Role.DEPARTMENT_MANAGER)) throw ApiException.forbidden();
        if (actor.managedDepartmentIds().isEmpty()) return List.of();
        return budgets.assignedMonth(actor.companyId(), period, actor.managedDepartmentIds()).stream().map(MonthlyBudget::view).toList();
    }

    /**
     * Normal path is unchanged: an existing period row is locked, the limit is validated against
     * the committed usage and the change is audited. When the period does not exist yet, the row
     * is created with a conflict-safe insert; a concurrent creator loses deterministically with a
     * defined 409 conflict instead of an unhandled constraint violation, and only the winning
     * creation is audited. The usage invariant is untouched either way.
     */
    @Transactional
    public BudgetView setLimit(UUID department, String month, LimitInput input) {
        var actor = access.command(false);
        actor.requireAdmin();
        directory.requireDepartment(actor.companyId(), department);
        var period = period(month);
        var existing = budgets.lockPeriod(actor.companyId(), department, period);
        if (existing.isPresent()) {
            var budget = existing.get();
            budget.changeLimit(input.limitAmount());
            budgets.saveAndFlush(budget);
            audit.record(actor, "budget.limit_set", budget.view().id());
            return budget.view();
        }
        var id = UUID.randomUUID();
        if (budgets.insertIfAbsent(id, actor.companyId(), department, period, input.limitAmount()) == 0) {
            throw ApiException.conflict("data_conflict");
        }
        var created = budgets.lockPeriod(actor.companyId(), department, period)
                .orElseThrow(() -> new IllegalStateException("Newly inserted budget period is not visible in this transaction"));
        audit.record(actor, "budget.limit_set", created.view().id());
        return created.view();
    }

    private LocalDate period(String value) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}")) throw ApiException.invalid("invalid_month");
            var yearMonth = YearMonth.parse(value);
            if (yearMonth.getYear() < 2000 || yearMonth.getYear() > 9999) throw ApiException.invalid("invalid_month");
            return yearMonth.atDay(1);
        } catch (DateTimeParseException exception) { throw ApiException.invalid("invalid_month"); }
    }
}

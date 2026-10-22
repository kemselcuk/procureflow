package com.procureflow.app.budgets;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface BudgetRepository extends Repository<MonthlyBudget, UUID> {
    MonthlyBudget saveAndFlush(MonthlyBudget budget);

    /**
     * Conflict-safe first-period creation: an absent period row is inserted with
     * {@code ON CONFLICT DO NOTHING}, so a concurrent creator waits on the unique budget period
     * instead of failing with an unhandled constraint violation. Returns 0 when another
     * transaction already created the period.
     */
    @Modifying
    @Query(value = """
            INSERT INTO monthly_budget (id, company_id, department_id, budget_month, limit_amount, used_amount, version)
            VALUES (:id, :company, :department, :month, :limit, 0, 0)
            ON CONFLICT ON CONSTRAINT uq_budget_period DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("company") UUID company, @Param("department") UUID department,
                       @Param("month") LocalDate month, @Param("limit") BigDecimal limit);

    @Query("select b from MonthlyBudget b where b.companyId = :company and b.budgetMonth = :month order by b.departmentId")
    List<MonthlyBudget> companyMonth(UUID company, LocalDate month);

    @Query("select b from MonthlyBudget b where b.companyId = :company and b.budgetMonth = :month and b.departmentId in :departments order by b.departmentId")
    List<MonthlyBudget> assignedMonth(UUID company, LocalDate month, Collection<UUID> departments);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MonthlyBudget b where b.companyId = :company and b.departmentId = :department and b.budgetMonth = :month")
    Optional<MonthlyBudget> lockPeriod(UUID company, UUID department, LocalDate month);
}

package com.procureflow.app.budgets;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface BudgetRepository extends Repository<MonthlyBudget, UUID> {
    MonthlyBudget saveAndFlush(MonthlyBudget budget);

    @Query("select b from MonthlyBudget b where b.companyId = :company and b.budgetMonth = :month order by b.departmentId")
    List<MonthlyBudget> companyMonth(UUID company, LocalDate month);

    @Query("select b from MonthlyBudget b where b.companyId = :company and b.budgetMonth = :month and b.departmentId in :departments order by b.departmentId")
    List<MonthlyBudget> assignedMonth(UUID company, LocalDate month, Collection<UUID> departments);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MonthlyBudget b where b.companyId = :company and b.departmentId = :department and b.budgetMonth = :month")
    Optional<MonthlyBudget> lockPeriod(UUID company, UUID department, LocalDate month);
}

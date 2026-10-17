package com.procureflow.app.budgets;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class BudgetController {
    private final BudgetService service;
    public BudgetController(BudgetService service) { this.service = service; }

    @GetMapping("/api/budgets")
    List<BudgetService.BudgetView> list(@RequestParam String month) { return service.list(month); }

    @PutMapping("/api/admin/departments/{department}/budgets/{month}")
    BudgetService.BudgetView set(@PathVariable UUID department, @PathVariable String month,
                               @Valid @RequestBody BudgetService.LimitInput input) {
        return service.setLimit(department, month, input);
    }
}

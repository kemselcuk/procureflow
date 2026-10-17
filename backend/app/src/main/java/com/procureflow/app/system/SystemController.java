package com.procureflow.app.system;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    @GetMapping("/api/system")
    @Operation(summary = "Describe the foundation service, not business-feature readiness")
    public SystemInfo system() {
        return new SystemInfo("ProcureFlow", "foundation");
    }

    public record SystemInfo(String name, String stage) {}
}

package com.procureflow.app.audit;

import com.procureflow.app.shared.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing/invalid bearer token: unauthorized", content = @Content),
    @ApiResponse(responseCode = "403", description = "Inactive membership or missing COMPANY_ADMIN role: forbidden", content = @Content)
})
public class AuditController {
    private final AuditQueries queries;

    AuditController(AuditQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(operationId = "getCompanyAudit",
            description = "Zero-based page (0-1000000), size 1-100. Read-only immutable audit metadata for the caller's current company only, in stable descending (occurred_at, id) order. Entity references do not grant access to the referenced entity; request content, identity claims and raw payloads are never exposed.")
    @ApiResponse(responseCode = "200", description = "Current-company audit metadata page",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditData.Page.class)))
    @ApiResponse(responseCode = "400", description = "Invalid page or size; page must be >= 0 and <= 1000000, size 1-100 (invalid_request)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    AuditData.Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.page(page, size);
    }
}

package com.procureflow.app.notifications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notifications")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing/invalid bearer token: unauthorized", content = @Content),
    @ApiResponse(responseCode = "403", description = "Inactive membership or missing COMPANY_ADMIN role: forbidden", content = @Content)
})
public class NotificationAdminController {
    private final NotificationQueries queries;

    NotificationAdminController(NotificationQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/backlog")
    @Operation(operationId = "getNotificationBacklog", description = "Current-company delivery backlog as fixed count fields: dispatch and email states, delivery outcomes and inbox total. All fields are always present, including zero; other companies' work, raw errors and recipient data are never exposed.")
    @ApiResponse(responseCode = "200", description = "Current-company backlog counts",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = NotificationData.Backlog.class)))
    NotificationData.Backlog backlog() {
        return queries.backlog();
    }
}

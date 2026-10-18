package com.procureflow.app.notifications;

import com.procureflow.app.shared.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing/invalid bearer token: unauthorized", content = @Content),
    @ApiResponse(responseCode = "403", description = "Inactive membership: forbidden", content = @Content)
})
public class NotificationController {
    private final NotificationCommands commands;
    private final NotificationQueries queries;

    NotificationController(NotificationCommands commands, NotificationQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    @Operation(operationId = "listNotifications", description = "Zero-based page, size 1-100. Current-company/current-identity in-app notifications only, in stable descending (created_at, id) order; event metadata is joined from the scoped outbox event.")
    @ApiResponse(responseCode = "200", description = "Current identity's notification page",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = NotificationData.Page.class)))
    @ApiResponse(responseCode = "400", description = "invalid_page; page outside 0..1000000 or size outside 1..100",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiErrors.ErrorBody.class)))
    NotificationData.Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.list(page, size);
    }

    @PostMapping("/{id}/read")
    @Operation(operationId = "markNotificationRead", description = "Marks one own-company/current-identity notification read. Idempotent: the first read time is preserved on retries. Unknown, foreign-company or other-owner IDs return 404; no request body is required.")
    @ApiResponse(responseCode = "200", description = "Notification ID and the persisted first read time",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = NotificationData.Read.class)))
    @ApiResponse(responseCode = "404", description = "Notification absent from the current company/identity scope", content = @Content)
    NotificationData.Read read(@PathVariable UUID id) {
        return commands.markRead(id);
    }
}

package com.procureflow.app.requests;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.procureflow.app.shared.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/requests")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "validation_failed or invalid_request; content/page/view validation errors",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiErrors.ErrorBody.class))),
    @ApiResponse(responseCode = "401", description = "Missing/invalid bearer token: unauthorized", content = @Content),
    @ApiResponse(responseCode = "403", description = "Inactive membership, role, ownership or department denial: forbidden", content = @Content)
})
public class RequestController {
    private final RequestCommands commands;
    private final RequestQueries queries;
    RequestController(RequestCommands commands, RequestQueries queries) { this.commands = commands; this.queries = queries; }

    @PostMapping
    @Operation(operationId = "createRequest", description = "Employee creates an own-company draft. Same UUID key and canonical fields replay the original response, not current resource state.")
    @ApiResponse(responseCode = "201", description = "Created or original idempotent result", content = @Content(schema = @Schema(implementation = RequestData.View.class)))
    @ApiResponse(responseCode = "409", description = "idempotency_key_reused, data_conflict or retryable_conflict; retry transient conflicts with the same key", content = @Content)
    ResponseEntity<RequestData.View> create(@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody RequestData.Create input) {
        return response(commands.create(key, input));
    }
    @PutMapping("/{id}")
    @Operation(operationId = "editRequest", description = "Employee owner edits a draft and refreshes its department from current membership. Expected version is required; completed retries conflict without duplicate effects.")
    @ApiResponse(responseCode = "404", description = "Request absent from current company", content = @Content)
    @ApiResponse(responseCode = "409", description = "stale_request, invalid_request_state or retryable_conflict", content = @Content)
    ResponseEntity<RequestData.View> edit(@PathVariable UUID id, @Valid @RequestBody RequestData.Edit input) {
        return response(commands.edit(id, input));
    }
    @PostMapping("/{id}/submit")
    @Operation(operationId = "submitRequest", description = "Employee owner submits a current-department draft. Same UUID key/target/original version replays its original success, after current authorization.")
    @ApiResponse(responseCode = "404", description = "Request absent from current company", content = @Content)
    @ApiResponse(responseCode = "409", description = "stale_request, invalid_request_state, request_department_changed, idempotency_key_reused or retryable_conflict", content = @Content)
    ResponseEntity<RequestData.View> submit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
                                           @Valid @RequestBody RequestData.Version input) {
        return response(commands.submit(id, key, input));
    }
    @PostMapping("/{id}/cancel")
    @Operation(operationId = "cancelRequest", description = "Owner cancels a pending request with an expected version; drafts and terminal requests cannot be cancelled.")
    @ApiResponse(responseCode = "404", description = "Request absent from current company", content = @Content)
    @ApiResponse(responseCode = "409", description = "stale_request, invalid_request_state or retryable_conflict", content = @Content)
    ResponseEntity<RequestData.View> cancel(@PathVariable UUID id, @Valid @RequestBody RequestData.Version input) {
        return response(commands.cancel(id, input));
    }
    @PostMapping("/{id}/reject")
    @Operation(operationId = "rejectRequest", description = "Assigned current-step approver rejects with a reason/version. Requester and prior department actor cannot reject the finance step.")
    @ApiResponse(responseCode = "404", description = "Request absent from current company", content = @Content)
    @ApiResponse(responseCode = "409", description = "stale_request, invalid_request_state or retryable_conflict", content = @Content)
    ResponseEntity<RequestData.View> reject(@PathVariable UUID id, @Valid @RequestBody RequestData.Reject input) {
        return response(commands.reject(id, input));
    }
    @PostMapping("/{id}/approve")
    @Operation(operationId = "approveRequest", description = "Assigned current-step approver; requester cannot approve, and the finance actor must differ from the department actor. Up to EUR 1000 department approval is final; larger amounts require finance. Final approval charges the captured UTC month atomically. Repeated completed commands conflict without duplicate effects.")
    @ApiResponse(responseCode = "200", description = "Committed approval and current request view",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RequestData.View.class)))
    @ApiResponse(responseCode = "404", description = "Request absent from current company", content = @Content)
    @ApiResponse(responseCode = "409", description = "stale_request, invalid_request_state, budget_missing, insufficient_budget or retryable_conflict", content = @Content)
    ResponseEntity<RequestData.View> approve(@PathVariable UUID id, @Valid @RequestBody RequestData.Version input) {
        return response(commands.approve(id, input));
    }
    @GetMapping("/{id}")
    @Operation(operationId = "getRequest", description = "Owner; assigned manager for non-drafts; or finance for above-threshold requests with actual department approval. Admin alone grants no request access.")
    @ApiResponse(responseCode = "404", description = "Request absent from current company", content = @Content)
    RequestData.View detail(@PathVariable UUID id) { return queries.detail(id); }
    @GetMapping("/{id}/history")
    @Operation(operationId = "getRequestHistory", description = "Same request read scope. Ordered immutable metadata and rejection reasons; no previous draft-content snapshots.")
    @ApiResponse(responseCode = "404", description = "Request absent from current company", content = @Content)
    List<RequestData.History> history(@PathVariable UUID id) { return queries.history(id); }
    @GetMapping
    @Operation(operationId = "listRequests", description = "Zero-based page, size 1-100. view=mine or actionable manager/finance inbox; optional uppercase status filter. Stable creation-time/ID order.")
    @ApiResponse(responseCode = "200", description = "One page of current-company request summaries for the selected view and status filter",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RequestData.Page.class)))
    RequestData.Page list(@RequestParam(defaultValue = "mine") String view, @RequestParam(required = false) RequestStatus status,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.list(view, status, page, size);
    }
    private static ResponseEntity<RequestData.View> response(RequestData.Result result) {
        return ResponseEntity.status(result.status()).header("X-Correlation-ID", result.body().correlationId().toString()).body(result.body());
    }
}

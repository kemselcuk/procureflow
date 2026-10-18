package com.procureflow.app.requests;

import com.procureflow.app.shared.ApiException;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RequestData {
    private RequestData() {}
    public record Create(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 2000) String justification,
                         @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount) {}
    public record Edit(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 200) String title,
                       @NotBlank @Size(max = 2000) String justification,
                       @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount) {}
    public record Version(@NotNull @PositiveOrZero Long version) {}
    public record Reject(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 1000) String reason) {}
    public record Content(String title, String justification, BigDecimal amount) {
        static Content of(String title, String justification, BigDecimal amount) {
            if (title.indexOf('\0') >= 0 || justification.indexOf('\0') >= 0) throw ApiException.invalid("invalid_request_content");
            return new Content(title.strip(), justification.strip(), amount.setScale(2));
        }
    }
    public record View(UUID id, UUID companyId, UUID requesterIdentityId, UUID departmentId, String title,
                       String justification, BigDecimal amount, String currency, RequestStatus status, long version,
                       Instant createdAt, Instant updatedAt, Instant submittedAt, UUID departmentApproverIdentityId,
                       UUID correlationId) {}
    public record Summary(UUID id, UUID departmentId, String title, BigDecimal amount, RequestStatus status,
                          long version, Instant createdAt) {}
    public record History(UUID id, long version, String action, String fromStatus, String toStatus,
                          UUID actorIdentityId, String reason, UUID correlationId, Instant occurredAt) {}
    public record Page(int page, int size, long total, List<Summary> items) {}
    public record Result(int status, View body) {}
}

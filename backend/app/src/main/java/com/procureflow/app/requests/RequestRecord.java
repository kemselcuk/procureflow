package com.procureflow.app.requests;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record RequestRecord(UUID id, UUID companyId, UUID ownerIdentityId, UUID ownerMembershipId, UUID departmentId,
                     String title, String justification, BigDecimal amount, RequestStatus status, long version,
                     Instant createdAt, Instant updatedAt, Instant submittedAt, UUID priorApproverIdentityId,
                     UUID priorApproverMembershipId, UUID correlationId, Instant approvedAt, java.time.LocalDate budgetMonth) {
    RequestData.View view() {
        return new RequestData.View(id, companyId, ownerIdentityId, departmentId, title, justification, amount,
                "EUR", status, version, createdAt, updatedAt, submittedAt, priorApproverIdentityId, correlationId,
                approvedAt, budgetMonth == null ? null : java.time.YearMonth.from(budgetMonth).toString());
    }
}

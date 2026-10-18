package com.procureflow.app.requests;

public enum RequestStatus {
    DRAFT, AWAITING_DEPARTMENT_APPROVAL, AWAITING_FINANCE_APPROVAL, APPROVED, REJECTED, CANCELLED;

    public boolean pending() {
        return this == AWAITING_DEPARTMENT_APPROVAL || this == AWAITING_FINANCE_APPROVAL;
    }
}

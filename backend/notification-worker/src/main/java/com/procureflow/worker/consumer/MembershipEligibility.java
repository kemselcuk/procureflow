package com.procureflow.worker.consumer;

import com.procureflow.events.RequestEvent;
import java.util.UUID;

/**
 * Pure member revalidation policy applied under the company authorization barrier, after the inbox
 * marker was claimed in the same transaction. The captured recipient links a membership id to an
 * identity and the event's company; a changed, foreign or inactive membership is skipped with a
 * fixed reason and is never rerouted to a different membership, role or company.
 *
 * <p>The checks are ordered: missing, company mismatch, identity mismatch, inactive. The first three
 * are defensive against an inconsistent snapshot (the frozen outbox-recipient foreign key normally
 * makes them unreachable); the inactive check is the ordinary revocation race.
 */
public final class MembershipEligibility {
    public static final String MISSING = "membership_missing";
    public static final String COMPANY_MISMATCH = "membership_company_mismatch";
    public static final String IDENTITY_MISMATCH = "membership_identity_mismatch";
    public static final String INACTIVE = "membership_inactive";

    private MembershipEligibility() {
    }

    /** Returns the fixed skip reason, or {@code null} when the recipient may be materialized. */
    public static String revalidate(UUID companyId, RequestEvent.Recipient recipient, MembershipState state) {
        if (state == null) {
            return MISSING;
        }
        if (!companyId.equals(state.companyId())) {
            return COMPANY_MISMATCH;
        }
        if (!recipient.identityId().equals(state.identityId())) {
            return IDENTITY_MISMATCH;
        }
        if (!state.active()) {
            return INACTIVE;
        }
        return null;
    }

    /** Worker-readable membership columns; no role, department or purchase data is read. */
    public record MembershipState(UUID id, UUID companyId, UUID identityId, boolean active) {
    }
}

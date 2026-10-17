package com.procureflow.app.identity;

import java.util.Set;
import java.util.UUID;

public record Actor(UUID identityId, UUID membershipId, UUID companyId, String companyName,
                    UUID departmentId, String departmentName, long version,
                    Set<Role> roles, Set<UUID> managedDepartmentIds) {
    public Actor {
        roles = Set.copyOf(roles);
        managedDepartmentIds = Set.copyOf(managedDepartmentIds);
    }
    public boolean has(Role role) { return roles.contains(role); }
    public void requireAdmin() {
        if (!has(Role.COMPANY_ADMIN)) throw com.procureflow.app.shared.ApiException.forbidden();
    }
}

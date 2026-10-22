package com.procureflow.app.identity;

import com.procureflow.app.shared.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class ActorAccess {
    private final JdbcTemplate jdbc;

    public ActorAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Jwt jwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) throw ApiException.forbidden();
        return jwt;
    }

    /**
     * One statement reads the current active membership, its roles and its managed departments
     * from the same PostgreSQL snapshot. A revoked or re-scoped membership therefore cannot be
     * observed in a partially updated state.
     */
    public Actor current() {
        var token = jwt();
        var memberships = jdbc.query("""
                SELECT i.id AS identity_id, m.id, m.company_id, c.name AS company_name,
                       m.department_id, d.name AS department_name, m.version,
                       ARRAY(SELECT r.role FROM membership_role r
                             WHERE r.company_id = m.company_id AND r.membership_id = m.id ORDER BY r.role) AS roles,
                       ARRAY(SELECT md.department_id FROM manager_department md
                             WHERE md.company_id = m.company_id AND md.membership_id = m.id
                             ORDER BY md.department_id) AS managed_department_ids
                FROM app_identity i JOIN membership m ON m.identity_id = i.id
                JOIN company c ON c.id = m.company_id
                JOIN department d ON d.company_id = m.company_id AND d.id = m.department_id
                WHERE i.issuer = ? AND i.subject = ? AND m.active
                """, (rs, index) -> new Actor(rs.getObject("identity_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("company_name"),
                rs.getObject("department_id", UUID.class), rs.getString("department_name"), rs.getLong("version"),
                AssignmentSets.roles(rs, "roles"), AssignmentSets.departments(rs, "managed_department_ids")),
                token.getIssuer().toString(), token.getSubject());
        if (memberships.size() != 1) throw ApiException.forbidden();
        return memberships.getFirst();
    }

    // Called inside the owning application service's transaction, before any business-row locks.
    public Actor command(boolean changesMembership) {
        var before = current();
        jdbc.queryForObject("SELECT id FROM company WHERE id = ? FOR " + (changesMembership ? "UPDATE" : "SHARE"),
                UUID.class, before.companyId());
        var actor = current();
        if (!actor.companyId().equals(before.companyId())) throw ApiException.conflict("authorization_changed");
        return actor;
    }
}

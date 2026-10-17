package com.procureflow.app.identity;

import com.procureflow.app.shared.ApiException;
import java.util.HashSet;
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

    public Actor current() {
        var token = jwt();
        var memberships = jdbc.query("""
                SELECT i.id AS identity_id, m.id, m.company_id, c.name AS company_name,
                       m.department_id, d.name AS department_name, m.version
                FROM app_identity i JOIN membership m ON m.identity_id = i.id
                JOIN company c ON c.id = m.company_id
                JOIN department d ON d.company_id = m.company_id AND d.id = m.department_id
                WHERE i.issuer = ? AND i.subject = ? AND m.active
                """, (rs, index) -> new Actor(rs.getObject("identity_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("company_name"),
                rs.getObject("department_id", UUID.class), rs.getString("department_name"), rs.getLong("version"),
                java.util.Set.of(), java.util.Set.of()), token.getIssuer().toString(), token.getSubject());
        if (memberships.size() != 1) throw ApiException.forbidden();
        var member = memberships.getFirst();
        var roles = new HashSet<>(jdbc.query("SELECT role FROM membership_role WHERE company_id = ? AND membership_id = ?",
                (rs, index) -> Role.valueOf(rs.getString(1)), member.companyId(), member.membershipId()));
        var departments = new HashSet<>(jdbc.query("SELECT department_id FROM manager_department WHERE company_id = ? AND membership_id = ?",
                (rs, index) -> rs.getObject(1, UUID.class), member.companyId(), member.membershipId()));
        return new Actor(member.identityId(), member.membershipId(), member.companyId(), member.companyName(),
                member.departmentId(), member.departmentName(), member.version(), roles, departments);
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

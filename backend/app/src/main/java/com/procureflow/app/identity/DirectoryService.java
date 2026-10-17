package com.procureflow.app.identity;

import com.procureflow.app.audit.AuditWriter;
import com.procureflow.app.shared.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectoryService {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;
    private final AuditWriter audit;

    public DirectoryService(JdbcTemplate jdbc, ActorAccess access, AuditWriter audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    public record IdentityView(UUID id, String issuer, String subject) {}
    public record DepartmentView(UUID id, String name) {}
    public record NewDepartment(@NotBlank @Size(max = 200) String name) {}
    public record NewMembership(@NotNull UUID identityId, @NotNull UUID departmentId,
                                @NotEmpty Set<@NotNull Role> roles, @NotNull Set<@NotNull UUID> managedDepartmentIds) {}
    public record UpdateMembership(@NotNull UUID departmentId, @NotNull Boolean active, @NotNull @PositiveOrZero Long version,
                                   @NotEmpty Set<@NotNull Role> roles, @NotNull Set<@NotNull UUID> managedDepartmentIds) {}
    public record MembershipView(UUID id, UUID identityId, UUID departmentId, boolean active, long version,
                                 Set<Role> roles, Set<UUID> managedDepartmentIds) {}

    @Transactional
    public IdentityView registerSelf() {
        var token = access.jwt();
        var issuer = token.getIssuer().toString();
        var subject = token.getSubject();
        if (issuer.length() > 512 || subject.length() > 255) throw ApiException.invalid("identity_too_long");
        jdbc.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING",
                UUID.randomUUID(), issuer, subject);
        return jdbc.queryForObject("SELECT id, issuer, subject FROM app_identity WHERE issuer = ? AND subject = ?",
                (rs, index) -> new IdentityView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), issuer, subject);
    }

    @Transactional(readOnly = true)
    public Actor me() { return access.current(); }

    @Transactional(readOnly = true)
    public List<DepartmentView> departments() {
        var actor = access.current();
        var permitted = new HashSet<UUID>();
        if (actor.has(Role.DEPARTMENT_MANAGER)) permitted.addAll(actor.managedDepartmentIds());
        if (actor.has(Role.EMPLOYEE)) permitted.add(actor.departmentId());
        boolean all = actor.has(Role.COMPANY_ADMIN) || actor.has(Role.FINANCE_APPROVER);
        return jdbc.query("SELECT id, name FROM department WHERE company_id = ? ORDER BY name",
                (rs, index) -> new DepartmentView(rs.getObject(1, UUID.class), rs.getString(2)), actor.companyId())
                .stream().filter(department -> all || permitted.contains(department.id())).toList();
    }

    @Transactional
    public DepartmentView createDepartment(NewDepartment input) {
        var actor = access.command(false);
        actor.requireAdmin();
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, ?)", id, actor.companyId(), input.name().strip());
        audit.record(actor, "department.created", id);
        return new DepartmentView(id, input.name().strip());
    }

    @Transactional(readOnly = true)
    public List<MembershipView> memberships() {
        var actor = access.current();
        actor.requireAdmin();
        var ids = jdbc.query("SELECT id FROM membership WHERE company_id = ? ORDER BY id",
                (rs, index) -> rs.getObject(1, UUID.class), actor.companyId());
        return ids.stream().map(id -> membership(actor.companyId(), id)).toList();
    }

    @Transactional
    public MembershipView createMembership(NewMembership input) {
        var actor = access.command(true);
        actor.requireAdmin();
        validateAssignments(actor.companyId(), input.departmentId(), input.roles(), input.managedDepartmentIds());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_identity WHERE id = ?)",
                Boolean.class, input.identityId()))) throw ApiException.notFound();
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) VALUES (?, ?, ?, ?, true)",
                id, actor.companyId(), input.identityId(), input.departmentId());
        replaceAssignments(actor.companyId(), id, input.roles(), input.managedDepartmentIds());
        audit.record(actor, "membership.created", id);
        return membership(actor.companyId(), id);
    }

    @Transactional
    public MembershipView updateMembership(UUID id, UpdateMembership input) {
        var actor = access.command(true);
        actor.requireAdmin();
        var before = membership(actor.companyId(), id);
        if (before.version() != input.version()) throw ApiException.conflict("stale_membership");
        validateAssignments(actor.companyId(), input.departmentId(), input.roles(), input.managedDepartmentIds());
        if (before.active() && before.roles().contains(Role.COMPANY_ADMIN)
                && (!input.active() || !input.roles().contains(Role.COMPANY_ADMIN))) {
            var others = jdbc.queryForObject("""
                    SELECT count(*) FROM membership m JOIN membership_role r
                    ON r.company_id = m.company_id AND r.membership_id = m.id
                    WHERE m.company_id = ? AND m.id <> ? AND m.active AND r.role = 'COMPANY_ADMIN'
                    """, Long.class, actor.companyId(), id);
            if (others == null || others == 0) throw ApiException.conflict("last_company_admin");
        }
        jdbc.update("UPDATE membership SET department_id = ?, active = ?, version = version + 1 WHERE company_id = ? AND id = ?",
                input.departmentId(), input.active(), actor.companyId(), id);
        replaceAssignments(actor.companyId(), id, input.roles(), input.managedDepartmentIds());
        audit.record(actor, "membership.updated", id);
        return membership(actor.companyId(), id);
    }

    private void validateAssignments(UUID company, UUID department, Set<Role> roles, Set<UUID> managed) {
        requireDepartment(company, department);
        if (roles.contains(Role.DEPARTMENT_MANAGER) != !managed.isEmpty()) throw ApiException.invalid("manager_assignments_required");
        managed.forEach(id -> requireDepartment(company, id));
    }

    public void requireDepartment(UUID company, UUID department) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM department WHERE company_id = ? AND id = ?)",
                Boolean.class, company, department))) throw ApiException.notFound();
    }

    private void replaceAssignments(UUID company, UUID member, Set<Role> roles, Set<UUID> managed) {
        jdbc.update("DELETE FROM manager_department WHERE company_id = ? AND membership_id = ?", company, member);
        jdbc.update("DELETE FROM membership_role WHERE company_id = ? AND membership_id = ?", company, member);
        roles.forEach(role -> jdbc.update("INSERT INTO membership_role VALUES (?, ?, ?)", company, member, role.name()));
        managed.forEach(department -> jdbc.update("INSERT INTO manager_department VALUES (?, ?, ?)", company, member, department));
    }

    private MembershipView membership(UUID company, UUID id) {
        var rows = jdbc.query("SELECT identity_id, department_id, active, version FROM membership WHERE company_id = ? AND id = ?",
                (rs, index) -> Map.<String, Object>of("identity", rs.getObject(1, UUID.class),
                        "department", rs.getObject(2, UUID.class), "active", rs.getBoolean(3), "version", rs.getLong(4)), company, id);
        if (rows.isEmpty()) throw ApiException.notFound();
        var row = rows.getFirst();
        var roles = new HashSet<>(jdbc.query("SELECT role FROM membership_role WHERE company_id = ? AND membership_id = ?",
                (rs, index) -> Role.valueOf(rs.getString(1)), company, id));
        var managed = new HashSet<>(jdbc.query("SELECT department_id FROM manager_department WHERE company_id = ? AND membership_id = ?",
                (rs, index) -> rs.getObject(1, UUID.class), company, id));
        return new MembershipView(id, (UUID) row.get("identity"), (UUID) row.get("department"),
                (boolean) row.get("active"), (long) row.get("version"), Set.copyOf(roles), Set.copyOf(managed));
    }
}

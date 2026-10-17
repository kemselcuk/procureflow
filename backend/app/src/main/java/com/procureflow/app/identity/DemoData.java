package com.procureflow.app.identity;

import com.procureflow.app.config.SecuritySettings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local-demo")
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoData implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SecuritySettings settings;
    public DemoData(JdbcTemplate jdbc, SecuritySettings settings) { this.jdbc = jdbc; this.settings = settings; }

    public static UUID id(String value) {
        return UUID.nameUUIDFromBytes(("procureflow-demo:" + value).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        for (var company : List.of("a", "b")) {
            var companyId = id(company);
            jdbc.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING",
                    companyId, "company-" + company, company.equals("a") ? "Larkvane" : "Northstar");
            for (var department : List.of("engineering", "operations")) {
                jdbc.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        id(company + ":" + department), companyId, department);
            }
            for (var entry : List.of("employee", "manager", "finance", "admin")) {
                var actor = company + ":" + entry;
                var identityId = id(actor + ":identity");
                var memberId = id(actor + ":membership");
                var departmentId = id(company + ":engineering");
                jdbc.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        identityId, settings.issuer(), id(actor + ":keycloak").toString());
                var created = jdbc.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) VALUES (?, ?, ?, ?, true) ON CONFLICT DO NOTHING",
                        memberId, companyId, identityId, departmentId);
                var role = switch (entry) {
                    case "employee" -> Role.EMPLOYEE;
                    case "manager" -> Role.DEPARTMENT_MANAGER;
                    case "finance" -> Role.FINANCE_APPROVER;
                    default -> Role.COMPANY_ADMIN;
                };
                // Seed assignments only for a newly created membership; never undo a later revocation.
                if (created == 1) {
                    jdbc.update("INSERT INTO membership_role VALUES (?, ?, ?) ON CONFLICT DO NOTHING", companyId, memberId, role.name());
                    if (role == Role.DEPARTMENT_MANAGER)
                        jdbc.update("INSERT INTO manager_department VALUES (?, ?, ?) ON CONFLICT DO NOTHING", companyId, memberId, departmentId);
                }
            }
        }
    }
}

package com.procureflow.app.requests;

import com.procureflow.app.identity.Actor;
import com.procureflow.app.identity.Role;
import com.procureflow.app.support.TestDatabase;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RequestUpgradeIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();

    @Test void realV3DraftAndOriginalCachedResponseSurviveV4MigrationAndReplay() throws Exception {
        // Owner-only fixture tests migration/data compatibility; runtime-role isolation is tested separately.
        var dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var jdbc = new JdbcTemplate(dataSource); var json = JsonMapper.builder().build();
        var before = Flyway.configure().dataSource(dataSource).target("3").load(); before.migrate();
        var company = UUID.randomUUID(); var department = UUID.randomUUID(); var identity = UUID.randomUUID();
        var membership = UUID.randomUUID(); var request = UUID.randomUUID(); var correlation = UUID.randomUUID(); var key = UUID.randomUUID();
        var now = Instant.parse("2026-10-18T10:00:00Z");
        jdbc.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, 'upgrade-fixture', 'Upgrade fixture', ?)", company, Timestamp.from(now));
        jdbc.update("INSERT INTO department VALUES (?, ?, 'Engineering')", department, company);
        jdbc.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', 'legacy')", identity);
        jdbc.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) VALUES (?, ?, ?, ?, true)", membership, company, identity, department);
        jdbc.update("INSERT INTO membership_role VALUES (?, ?, 'EMPLOYEE')", company, membership);
        jdbc.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                title, justification, amount, created_at, updated_at, correlation_id) VALUES (?, ?, ?, ?, ?, 'Legacy draft', 'Legacy justification', 50, ?, ?, ?)
                """, request, company, identity, membership, department, Timestamp.from(now), Timestamp.from(now), correlation);
        jdbc.update("""
                INSERT INTO request_history (id, company_id, request_id, request_version, action, to_status,
                actor_identity_id, actor_membership_id, correlation_id, occurred_at) VALUES (?, ?, ?, 0, 'request.created', 'DRAFT', ?, ?, ?, ?)
                """, UUID.randomUUID(), company, request, identity, membership, correlation, Timestamp.from(now));
        var content = new RequestData.Content("Legacy draft", "Legacy justification", new BigDecimal("50.00"));
        var fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of("CREATE", content))));
        var legacy = """
                {"id":"%s","companyId":"%s","requesterIdentityId":"%s","departmentId":"%s",
                "title":"Legacy draft","justification":"Legacy justification","amount":50.00,"currency":"EUR",
                "status":"DRAFT","version":0,"createdAt":"%s","updatedAt":"%s","submittedAt":null,
                "departmentApproverIdentityId":null,"correlationId":"%s"}
                """.formatted(request, company, identity, department, now, now, correlation);
        jdbc.update("""
                INSERT INTO api_command (company_id, actor_identity_id, idempotency_key, operation, fingerprint,
                response_status, response_json, created_at) VALUES (?, ?, ?, 'CREATE', ?, 201, CAST(? AS jsonb), ?)
                """, company, identity, key, fingerprint, legacy, Timestamp.from(now));
        var stored = jdbc.queryForObject("SELECT response_json::text FROM api_command", String.class);
        assertThat(stored).doesNotContain("approvedAt", "budgetMonth");
        var migrated = Flyway.configure().dataSource(dataSource).target("4").load(); migrated.migrate();
        assertThat(migrated.info().current().getVersion().getVersion()).isEqualTo("4");
        var actor = new Actor(identity, membership, company, "Upgrade fixture", department, "Engineering", 0, Set.of(Role.EMPLOYEE), Set.of());
        var replay = new CommandResults(jdbc, json).claim(actor, key, "CREATE", content, now);
        assertThat(replay.status()).isEqualTo(201); assertThat(replay.body().id()).isEqualTo(request);
        assertThat(replay.body().amount()).isEqualByComparingTo("50.00");
        assertThat(replay.body().correlationId()).isEqualTo(correlation);
        assertThat(replay.body().approvedAt()).isNull(); assertThat(replay.body().budgetMonth()).isNull();
        assertThat(jdbc.queryForObject("SELECT response_json::text FROM api_command", String.class)).isEqualTo(stored);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_request", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_command", Integer.class)).isEqualTo(1);
    }
}

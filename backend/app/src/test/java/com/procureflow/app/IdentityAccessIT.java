package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.procureflow.app.identity.DemoData.id;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityAccessIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired DemoData demo;
    @Autowired JdbcTemplate applicationJdbc;
    JdbcTemplate owner;
    HttpClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
        properties.add("app.security.issuer", TOKENS::issuer);
        properties.add("app.security.jwk-set-uri", TOKENS::jwks);
    }

    @BeforeEach
    void resetRealDatabase() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("TRUNCATE audit_entry, manager_department, membership_role, monthly_budget, membership, department, app_identity, company CASCADE");
        demo.run(null);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void closeClient() { client.close(); }
    @AfterAll static void stopJwks() { TOKENS.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "signature", "subject", "type", "future", "no-expiry"})
    void rejectsInvalidRealSignedTokens(String defect) throws Exception {
        var token = TOKENS.token(id("a:admin:keycloak").toString(), claims -> {
            switch (defect) {
                case "issuer" -> claims.issuer("https://attacker.invalid/realms/other");
                case "audience" -> claims.audience("another-api");
                case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(3600)));
                case "subject" -> claims.subject(null);
                case "type" -> claims.claim("typ", "ID");
                case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(3600)));
                case "no-expiry" -> claims.expirationTime(null);
                default -> { }
            }
        }, defect.equals("signature"));
        assertThat(sendToken(token, "/api/me", "GET", null).statusCode()).isEqualTo(401);
    }

    @Test
    void requiresBearerAndIgnoresTokenBusinessRolesAndSpoofedCompanyHeaders() throws Exception {
        assertThat(sendToken(null, "/api/me", "GET", null).statusCode()).isEqualTo(401);
        assertThat(send("a:employee", "/api/admin/memberships", "GET", null).statusCode()).isEqualTo(403);
        var request = HttpRequest.newBuilder(url("/api/me")).header("Authorization", "Bearer " + token("a:employee"))
                .header("X-Company-Id", id("b").toString()).GET().build();
        var result = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(node(result).path("companyId").asText()).isEqualTo(id("a").toString());
    }

    @Test
    void bootstrapsOnlyOwnIdentityWithoutGrantingCompanyAuthority() throws Exception {
        var subject = UUID.randomUUID().toString();
        var token = TOKENS.token(subject);
        var first = sendToken(token, "/api/identities/self", "POST", null);
        var replay = sendToken(token, "/api/identities/self", "POST", null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(node(first).path("id").asText()).isEqualTo(node(replay).path("id").asText());
        assertThat(sendToken(token, "/api/me", "GET", null).statusCode()).isEqualTo(403);
        var identity = node(first).path("id").asText();
        var input = Map.of("identityId", identity, "departmentId", id("a:engineering"),
                "roles", List.of("EMPLOYEE"), "managedDepartmentIds", List.of());
        assertThat(send("a:admin", "/api/admin/memberships", "POST", input).statusCode()).isEqualTo(201);
        var foreignInput = Map.of("identityId", identity, "departmentId", id("b:engineering"),
                "roles", List.of("EMPLOYEE"), "managedDepartmentIds", List.of());
        assertThat(send("b:admin", "/api/admin/memberships", "POST", foreignInput).statusCode()).isEqualTo(409);
        assertThat(node(sendToken(token, "/api/me", "GET", null)).path("companyId").asText()).isEqualTo(id("a").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"employee", "manager", "finance", "admin"})
    void departmentListsRespectRoleAndCompany(String role) throws Exception {
        var response = send("a:" + role, "/api/departments", "GET", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var departments = node(response);
        assertThat(departments.size()).isEqualTo(role.equals("employee") || role.equals("manager") ? 1 : 2);
        assertThat(departments.toString()).doesNotContain(id("b:engineering").toString(), id("b:operations").toString());
    }

    @Test
    void adminMutationsAreScopedAndAuditedInTheirCompany() throws Exception {
        assertThat(send("a:employee", "/api/admin/departments", "POST", Map.of("name", "Denied")).statusCode()).isEqualTo(403);
        assertThat(send("a:admin", "/api/admin/departments", "POST", Map.of("name", "New department")).statusCode()).isEqualTo(201);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE company_id = ?", Integer.class, id("a"))).isEqualTo(1);
        var members = send("a:admin", "/api/admin/memberships", "GET", null);
        assertThat(node(members).size()).isEqualTo(4);
        assertThat(members.body()).doesNotContain(id("b:employee:identity").toString());
        assertThat(send("a:admin", "/api/admin/memberships/" + id("b:employee:membership"), "PUT", update("b:engineering", true, 0, "EMPLOYEE")).statusCode()).isEqualTo(404);
        var foreign = Map.of("identityId", id("a:employee:identity"), "departmentId", id("b:engineering"),
                "roles", List.of("EMPLOYEE"), "managedDepartmentIds", List.of());
        assertThat(send("a:admin", "/api/admin/memberships", "POST", foreign).statusCode()).isEqualTo(404);
        assertThat(send("a:admin", budget("b:engineering"), "PUT", Map.of("limitAmount", 100)).statusCode()).isEqualTo(404);
    }

    @Test
    void revocationAndRoleChangesTakeEffectWithTheSameUnexpiredToken() throws Exception {
        var oldToken = token("a:manager");
        assertThat(sendToken(oldToken, "/api/budgets?month=2026-10", "GET", null).statusCode()).isEqualTo(200);
        owner.update("DELETE FROM membership_role WHERE company_id = ? AND membership_id = ?", id("a"), id("a:manager:membership"));
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'EMPLOYEE')", id("a"), id("a:manager:membership"));
        owner.update("INSERT INTO manager_department VALUES (?, ?, ?)", id("a"), id("a:manager:membership"), id("a:operations"));
        assertThat(sendToken(oldToken, "/api/budgets?month=2026-10", "GET", null).statusCode()).isEqualTo(403);
        assertThat(node(sendToken(oldToken, "/api/departments", "GET", null)).toString()).doesNotContain(id("a:operations").toString());
        assertThat(send("a:admin", "/api/admin/memberships/" + id("a:manager:membership"), "PUT", update("a:engineering", false, 0, "EMPLOYEE")).statusCode()).isEqualTo(200);
        assertThat(sendToken(oldToken, "/api/me", "GET", null).statusCode()).isEqualTo(403);
        demo.run(null);
        assertThat(sendToken(oldToken, "/api/me", "GET", null).statusCode()).isEqualTo(403);
    }

    @Test
    void lastAdminAndStaleUpdatesDoNotChangeStateOrAudit() throws Exception {
        var adminPath = "/api/admin/memberships/" + id("a:admin:membership");
        var denied = send("a:admin", adminPath, "PUT", update("a:engineering", false, 0, "COMPANY_ADMIN"));
        assertThat(denied.statusCode()).isEqualTo(409);
        assertThat(node(denied).path("code").asText()).isEqualTo("last_company_admin");
        var employeePath = "/api/admin/memberships/" + id("a:employee:membership");
        var change = update("a:operations", true, 0, "EMPLOYEE");
        assertThat(send("a:admin", employeePath, "PUT", change).statusCode()).isEqualTo(200);
        assertThat(send("a:admin", employeePath, "PUT", change).statusCode()).isEqualTo(409);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry", Integer.class)).isEqualTo(1);
    }

    @Test
    void budgetReadScopesAndLimitEditsCannotRewriteUsage() throws Exception {
        for (var company : List.of("a", "b")) for (var department : List.of("engineering", "operations"))
            assertThat(send(company + ":admin", budget(company + ":" + department), "PUT", Map.of("limitAmount", 100)).statusCode()).isEqualTo(200);
        assertThat(send("a:employee", "/api/budgets?month=2026-10", "GET", null).statusCode()).isEqualTo(403);
        assertThat(node(send("a:manager", "/api/budgets?month=2026-10", "GET", null)).size()).isEqualTo(1);
        var companyBudgets = node(send("a:finance", "/api/budgets?month=2026-10", "GET", null));
        assertThat(companyBudgets.size()).isEqualTo(2);
        assertThat(companyBudgets.toString()).doesNotContain(id("b:engineering").toString());
        owner.update("UPDATE monthly_budget SET used_amount = 5 WHERE company_id = ? AND department_id = ?", id("a"), id("a:engineering"));
        assertThat(send("a:admin", budget("a:engineering"), "PUT", Map.of("limitAmount", 4)).statusCode()).isEqualTo(409);
        var result = send("a:admin", budget("a:engineering"), "PUT", Map.of("limitAmount", 6));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(node(result).path("usedAmount").decimalValue()).isEqualByComparingTo("5");
        assertThat(node(result).path("remainingAmount").decimalValue()).isEqualByComparingTo("1");
        assertThat(send("a:admin", budget("a:engineering"), "PUT", Map.of("limitAmount", 100, "usedAmount", 0)).statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.001", "1000000000000.00"})
    void rejectsInvalidBudgetDecimalValuesWithoutRounding(String amount) throws Exception {
        assertThat(send("a:admin", budget("a:engineering"), "PUT", Map.of("limitAmount", new BigDecimal(amount))).statusCode()).isEqualTo(400);
        assertThat(owner.queryForObject("SELECT count(*) FROM monthly_budget", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"below-usage", "negative-usage"})
    void databaseRejectsInvalidBudgetWritesEvenWithoutTheServiceGuard(String violation) throws Exception {
        assertThat(send("a:admin", budget("a:engineering"), "PUT", Map.of("limitAmount", 10)).statusCode()).isEqualTo(200);
        owner.update("UPDATE monthly_budget SET used_amount = 5 WHERE company_id = ? AND department_id = ?",
                id("a"), id("a:engineering"));
        var sql = violation.equals("below-usage")
                ? "UPDATE monthly_budget SET limit_amount = 4 WHERE company_id = ? AND department_id = ?"
                : "UPDATE monthly_budget SET used_amount = -0.01 WHERE company_id = ? AND department_id = ?";
        assertThatThrownBy(() -> applicationJdbc.update(sql, id("a"), id("a:engineering")))
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        var persisted = applicationJdbc.queryForMap("SELECT limit_amount, used_amount FROM monthly_budget WHERE company_id = ? AND department_id = ?",
                id("a"), id("a:engineering"));
        assertThat((BigDecimal) persisted.get("limit_amount")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) persisted.get("used_amount")).isEqualByComparingTo("5.00");
    }

    @Test
    void runtimeCannotAlterSchemaOrRewriteAuditAndTenantForeignKeysRejectSpoofing() throws Exception {
        assertThat(applicationJdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("procureflow_app");
        assertThat(applicationJdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
        assertThatThrownBy(() -> applicationJdbc.execute("CREATE TABLE forbidden_table (id int)")).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> applicationJdbc.update("DELETE FROM audit_entry")).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> applicationJdbc.update("UPDATE audit_entry SET action = 'forged'")).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> applicationJdbc.update("DELETE FROM flyway_schema_history")).hasRootCauseInstanceOf(SQLException.class);
        assertThat(owner.queryForObject("SELECT has_database_privilege('procureflow_app', 'keycloak', 'CONNECT')", Boolean.class)).isFalse();
        assertThatThrownBy(() -> applicationJdbc.update("UPDATE membership SET department_id = ? WHERE id = ?",
                id("b:engineering"), id("a:employee:membership"))).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO manager_department VALUES (?, ?, ?)",
                id("a"), id("a:manager:membership"), id("b:engineering"))).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO monthly_budget (id, company_id, department_id, budget_month, limit_amount) VALUES (?, ?, ?, '2026-10-01', 100)",
                UUID.randomUUID(), id("a"), id("b:engineering"))).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id) VALUES (?, ?, ?, ?, 'forged', ?)",
                UUID.randomUUID(), id("a"), id("b:admin:identity"), id("a:admin:membership"), UUID.randomUUID())).hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void auditFailureRollsBackTheAdministrationMutation() throws Exception {
        owner.execute("REVOKE INSERT ON audit_entry FROM procureflow_app");
        try {
            var response = send("a:admin", "/api/admin/departments", "POST", Map.of("name", "Must roll back"));
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(owner.queryForObject("SELECT count(*) FROM department WHERE name = 'Must roll back'", Integer.class)).isZero();
        } finally { owner.execute("GRANT INSERT ON audit_entry TO procureflow_app"); }
    }

    @Test
    void commandRechecksMembershipAfterWaitingForTheAuthorizationLock() throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM company WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, id("a")); lock.executeQuery().close();
            }
            var response = executor.submit(() -> send("a:admin", "/api/admin/departments", "POST", Map.of("name", "Revoked command")));
            var deadline = Instant.now().plusSeconds(8);
            boolean waiting = false;
            while (Instant.now().isBefore(deadline)) {
                waiting = Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE usename = 'procureflow_app' AND wait_event_type = 'Lock' AND query LIKE '%FOR SHARE%')", Boolean.class));
                if (waiting) break;
                Thread.sleep(20);
            }
            if (!waiting) { connection.rollback(); fail("Command did not reach the database authorization barrier"); }
            try (var revoke = connection.prepareStatement("UPDATE membership SET active = false WHERE id = ?")) {
                revoke.setObject(1, id("a:admin:membership")); revoke.executeUpdate();
            }
            connection.commit();
            assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(403);
            assertThat(owner.queryForObject("SELECT count(*) FROM department WHERE name = 'Revoked command'", Integer.class)).isZero();
        }
    }

    @Test
    void corsAllowsOnlyTheConfiguredFrontendOrigin() throws Exception {
        for (var origin : List.of("http://localhost:4200", "https://attacker.invalid")) {
            var request = HttpRequest.newBuilder(url("/api/me")).header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(origin.contains("localhost") ? 200 : 403);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin").orElse("")).isEqualTo(origin.contains("localhost") ? origin : "");
        }
    }

    @Test
    void concurrentAdministratorRevocationsKeepOneActiveAdministrator() throws Exception {
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'COMPANY_ADMIN')", id("a"), id("a:employee:membership"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return send("a:admin", "/api/admin/memberships/" + id("a:employee:membership"), "PUT", update("a:engineering", false, 0, "COMPANY_ADMIN")).statusCode();
            });
            var second = executor.submit(() -> {
                start.await();
                return send("a:employee", "/api/admin/memberships/" + id("a:admin:membership"), "PUT", update("a:engineering", false, 0, "COMPANY_ADMIN")).statusCode();
            });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 403);
            assertThat(owner.queryForObject("SELECT count(*) FROM membership m JOIN membership_role r ON r.company_id = m.company_id AND r.membership_id = m.id WHERE m.company_id = ? AND m.active AND r.role = 'COMPANY_ADMIN'", Long.class, id("a"))).isEqualTo(1);
        }
    }

    private String token(String actor) { return TOKENS.token(id(actor + ":keycloak").toString()); }
    private URI url(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String budget(String department) { return "/api/admin/departments/" + id(department) + "/budgets/2026-10"; }
    private Map<String, Object> update(String department, boolean active, long version, String role) {
        return Map.of("departmentId", id(department), "active", active, "version", version, "roles", List.of(role), "managedDepartmentIds", List.of());
    }
    private HttpResponse<String> send(String actor, String path, String method, Object body) throws Exception {
        return sendToken(token(actor), path, method, body);
    }
    private HttpResponse<String> sendToken(String token, String path, String method, Object body) throws Exception {
        var request = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode node(HttpResponse<String> response) { return JSON.readTree(response.body()); }
}

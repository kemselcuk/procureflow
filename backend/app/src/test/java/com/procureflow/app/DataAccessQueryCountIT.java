package com.procureflow.app;

import com.procureflow.app.identity.Actor;
import com.procureflow.app.identity.ActorAccess;
import com.procureflow.app.identity.DemoData;
import com.procureflow.app.identity.DirectoryService;
import com.procureflow.app.identity.Role;
import com.procureflow.app.support.CountingDataSourceConfiguration;
import com.procureflow.app.support.QueryFixtures;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.procureflow.app.identity.DemoData.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8.2 query-count evidence on a deterministic 100-member fixture. The counting DataSource wraps
 * the real pool, so the observed numbers are the statements the production services actually
 * execute against real PostgreSQL. Counts are budgets for this fixture, not latency claims.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@Import(CountingDataSourceConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataAccessQueryCountIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired DemoData demo;
    @Autowired DirectoryService directory;
    @Autowired ActorAccess access;
    @Autowired PlatformTransactionManager transactionManager;
    JdbcTemplate owner;
    HttpClient client;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
        properties.add("app.security.issuer", TOKENS::issuer);
        properties.add("app.security.jwk-set-uri", TOKENS::jwks);
    }

    @BeforeEach void reset() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("""
                TRUNCATE notification_diagnostic, email_job, in_app_notification, notification_delivery, notification_inbox,
                    outbox_dispatch, outbox_recipient, outbox_event, request_history, api_command, budget_charge,
                    approval_step, audit_entry, manager_department, membership_role, monthly_budget, membership,
                    department, app_identity, company CASCADE
                """);
        demo.run(null);
        QueryFixtures.seedMembers(owner, id("a"), id("a:engineering"), id("a:operations"), 96);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        if (client != null) client.close();
    }

    @AfterAll static void closeTokens() { TOKENS.close(); }

    @Test
    void adminMembershipListUsesBoundedBulkQueriesAndExcludesForeignCompany() throws Exception {
        authenticate("a:admin");
        var counting = CountingDataSourceConfiguration.counting();
        counting.reset();
        var members = directory.memberships();
        System.out.println("[QUERY-COUNT] admin membership list over 100 members: " + counting.count()
                + " statements (bulk membership queries=" + counting.countMatching("FROM membership m") + ")");
        assertThat(members).hasSize(100);
        assertThat(counting.count()).as(counting.statements()::toString).isLessThanOrEqualTo(4);
        assertThat(counting.countMatching("FROM membership m")).isEqualTo(1);

        var manager = members.stream().filter(member -> member.roles().contains(Role.DEPARTMENT_MANAGER)).findFirst().orElseThrow();
        assertThat(manager.roles()).containsExactly(Role.DEPARTMENT_MANAGER);
        assertThat(manager.managedDepartmentIds()).containsExactly(id("a:operations"));
        var employee = members.stream().filter(member -> member.roles().contains(Role.EMPLOYEE)).findFirst().orElseThrow();
        assertThat(employee.roles()).containsExactly(Role.EMPLOYEE);
        assertThat(employee.managedDepartmentIds()).isEmpty();

        Set<UUID> foreignIdentities = Set.of(id("b:employee:identity"), id("b:manager:identity"),
                id("b:finance:identity"), id("b:admin:identity"));
        assertThat(members).noneMatch(member -> foreignIdentities.contains(member.identityId()));

        // The HTTP contract keeps the same fields and role mapping as before the bulk rewrite.
        var response = get(token("a:admin"), "/api/admin/memberships");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = JSON.readTree(response.body());
        assertThat(body.size()).isEqualTo(100);
        JsonNode managerEntry = null;
        for (var entry : body) {
            if (entry.path("id").asText().equals(manager.id().toString())) managerEntry = entry;
            assertThat(entry.path("identityId").asText()).isNotIn(foreignIdentities.stream().map(UUID::toString).toList());
        }
        assertThat(managerEntry).isNotNull();
        assertThat(managerEntry.path("roles").toString()).contains("DEPARTMENT_MANAGER");
        assertThat(managerEntry.path("managedDepartmentIds").toString()).contains(id("a:operations").toString());
        assertThat(managerEntry.path("active").asBoolean()).isTrue();
    }

    @Test
    void currentActorIsReadFromOneSnapshotStatement() {
        authenticate("a:manager");
        var counting = CountingDataSourceConfiguration.counting();
        counting.reset();
        var actor = access.current();
        System.out.println("[QUERY-COUNT] ActorAccess.current(): " + counting.count() + " statement(s)");
        assertThat(counting.count()).as(counting.statements()::toString).isEqualTo(1);
        assertThat(actor.companyId()).isEqualTo(id("a"));
        assertThat(actor.membershipId()).isEqualTo(id("a:manager:membership"));
        assertThat(actor.roles()).containsExactly(Role.DEPARTMENT_MANAGER);
        assertThat(actor.managedDepartmentIds()).containsExactly(id("a:engineering"));
        assertThat(actor.version()).isZero();
    }

    @Test
    void commandReReadsTheActorAroundTheCompanyBarrier() {
        authenticate("a:admin");
        var counting = CountingDataSourceConfiguration.counting();
        var transactions = new TransactionTemplate(transactionManager);
        counting.reset();
        var actor = transactions.execute(status -> access.command(false));
        assertThat(actor).isNotNull();
        System.out.println("[QUERY-COUNT] ActorAccess.command(false): " + counting.count()
                + " statements (actor reads=" + counting.countMatching("FROM app_identity") + ", company barrier="
                + counting.countMatching("FOR SHARE") + ")");
        assertThat(counting.count()).as(counting.statements()::toString).isEqualTo(3);
        assertThat(counting.countMatching("FROM app_identity")).isEqualTo(2);
        assertThat(counting.countMatching("FOR SHARE")).isEqualTo(1);

        counting.reset();
        var updating = transactions.execute(status -> access.command(true));
        assertThat(updating).isNotNull();
        assertThat(counting.count()).as(counting.statements()::toString).isEqualTo(3);
        assertThat(counting.countMatching("FOR UPDATE")).isEqualTo(1);
    }

    private void authenticate(String actor) {
        var jwt = Jwt.withTokenValue("query-count-fixture").header("alg", "RS256")
                .issuer(TOKENS.issuer()).subject(id(actor + ":keycloak").toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private String token(String actor) { return TOKENS.token(id(actor + ":keycloak").toString()); }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

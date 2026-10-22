package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * P8.2 first-budget-period race on real PostgreSQL: two administrators concurrently create the
 * absent month/department row. A test-only trigger holds both INSERTs on one advisory lock so the
 * overlap is deterministic rather than timing-dependent. Expected outcome: exactly one row, one
 * committed limit change with its audit entry, defined 409 for the loser (never a 500) and the
 * usage invariant untouched.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BudgetFirstPeriodRaceIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String MONTH = "2026-12";
    static final long BARRIER_KEY = 880217L;

    @LocalServerPort int port;
    @Autowired DemoData demo;
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
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void dropFixtureObjects() {
        if (owner != null) {
            owner.execute("DROP TRIGGER IF EXISTS fixture_budget_barrier ON monthly_budget");
            owner.execute("DROP FUNCTION IF EXISTS fixture_budget_barrier()");
        }
        if (client != null) client.close();
    }

    @AfterAll static void closeTokens() { TOKENS.close(); }

    @Test
    void concurrentFirstPeriodCreationHasOneRowOneAuditAndDefinedResponses() throws Exception {
        try (Connection barrier = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            try (var statement = barrier.createStatement()) {
                statement.execute("SELECT pg_advisory_lock(" + BARRIER_KEY + ")");
            }
            owner.execute("""
                    CREATE FUNCTION fixture_budget_barrier() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        PERFORM pg_advisory_xact_lock(%d);
                        RETURN NEW;
                    END $$
                    """.formatted(BARRIER_KEY));
            owner.execute("CREATE TRIGGER fixture_budget_barrier BEFORE INSERT ON monthly_budget FOR EACH ROW EXECUTE FUNCTION fixture_budget_barrier()");

            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> { start.await(); return setLimit("100.00"); });
                var second = executor.submit(() -> { start.await(); return setLimit("250.00"); });
                start.countDown();
                awaitBlockedBudgetInserts(2);
                try (var statement = barrier.createStatement()) {
                    statement.execute("SELECT pg_advisory_unlock(" + BARRIER_KEY + ")");
                }
                var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
                assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
                var winner = responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow();
                var loser = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
                assertThat(node(loser).path("code").asText()).as(loser.body()).isEqualTo("data_conflict");

                var budget = owner.queryForMap("""
                        SELECT id, limit_amount, used_amount, version FROM monthly_budget
                        WHERE company_id = ? AND department_id = ? AND budget_month = DATE '2026-12-01'
                        """, id("a"), id("a:engineering"));
                assertThat(owner.queryForObject("""
                        SELECT count(*) FROM monthly_budget
                        WHERE company_id = ? AND department_id = ? AND budget_month = DATE '2026-12-01'
                        """, Long.class, id("a"), id("a:engineering"))).isEqualTo(1);
                assertThat((BigDecimal) budget.get("used_amount")).isEqualByComparingTo("0");
                assertThat(budget.get("version")).isEqualTo(0L);
                var persistedLimit = (BigDecimal) budget.get("limit_amount");
                assertThat(node(winner).path("limitAmount").decimalValue()).isEqualByComparingTo(persistedLimit);
                assertThat(node(winner).path("usedAmount").decimalValue()).isEqualByComparingTo("0");
                assertThat(node(winner).path("remainingAmount").decimalValue()).isEqualByComparingTo(persistedLimit);
                assertThat(node(winner).path("id").asText()).isEqualTo(budget.get("id").toString());
                assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE action = 'budget.limit_set' AND entity_id = ?",
                        Long.class, budget.get("id"))).isEqualTo(1);
                assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry", Long.class)).isEqualTo(1);
            } finally {
                try (var statement = barrier.createStatement()) {
                    statement.execute("SELECT pg_advisory_unlock(" + BARRIER_KEY + ")");
                }
            }
        }
        owner.execute("DROP TRIGGER IF EXISTS fixture_budget_barrier ON monthly_budget");
        owner.execute("DROP FUNCTION IF EXISTS fixture_budget_barrier()");

        // Normal single-request behavior is unchanged: the existing row is locked, updated and audited.
        var update = setLimit("300.00");
        assertThat(update.statusCode()).as(update.body()).isEqualTo(200);
        assertThat(node(update).path("limitAmount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(node(update).path("usedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE action = 'budget.limit_set'", Long.class)).isEqualTo(2);
    }

    private void awaitBlockedBudgetInserts(int expected) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        long blocked = 0;
        while (System.nanoTime() < deadline) {
            blocked = owner.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE usename = 'procureflow_app' AND wait_event_type = 'Lock'
                      AND lower(query) LIKE 'insert into monthly_budget%'
                    """, Long.class);
            if (blocked >= expected) return;
            Thread.sleep(20);
        }
        fail("expected %d concurrent first-period inserts to wait on the fixture barrier, observed %d".formatted(expected, blocked));
    }

    private HttpResponse<String> setLimit(String amount) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/admin/departments/" + id("a:engineering") + "/budgets/" + MONTH))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + TOKENS.token(id("a:admin:keycloak").toString()))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("limitAmount", new BigDecimal(amount)))))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode node(HttpResponse<String> response) { return JSON.readTree(response.body()); }
}

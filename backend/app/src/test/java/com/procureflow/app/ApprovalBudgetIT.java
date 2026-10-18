package com.procureflow.app;

import com.procureflow.app.budgets.BudgetCharger;
import com.procureflow.app.identity.DemoData;
import com.procureflow.app.requests.DemoRequestData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.procureflow.app.identity.DemoData.id;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local-demo")
@Import(ApprovalBudgetIT.TestTime.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalBudgetIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant OCTOBER = Instant.parse("2026-10-31T23:59:59.999999999Z");
    static final Instant NOVEMBER = Instant.parse("2026-11-01T00:00:00.000000999Z");
    static class MutableClock extends Clock {
        final AtomicReference<Instant> current = new AtomicReference<>(OCTOBER);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }
    @TestConfiguration static class TestTime { @Bean @Primary MutableClock testClock() { return new MutableClock(); } }
    @Autowired DemoData demo;
    @Autowired DemoRequestData demoRequests;
    @Autowired MutableClock clock;
    @Autowired BudgetCharger charger;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate applicationJdbc;
    @LocalServerPort int port;
    JdbcTemplate owner;
    HttpClient client;
    final Map<String, String> tokens = new ConcurrentHashMap<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
        properties.add("app.security.issuer", TOKENS::issuer); properties.add("app.security.jwk-set-uri", TOKENS::jwks);
    }
    @BeforeEach void reset() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("TRUNCATE audit_entry, app_identity, company CASCADE"); demo.run(null);
        clock.current.set(OCTOBER); tokens.clear();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterEach void close() {
        if (client != null) client.close();
        if (owner != null) {
            owner.execute("DROP TRIGGER IF EXISTS injected_failure ON budget_charge");
            owner.execute("DROP FUNCTION IF EXISTS injected_failure()");
            owner.execute("DROP SEQUENCE IF EXISTS flush_observed");
        }
    }
    @AfterAll static void closeTokens() { TOKENS.close(); }

    @ParameterizedTest @ValueSource(strings = {"999.99", "1000.00", "1000.01"})
    void thresholdRoutesUseRealApprovalsAndChargeOnlyTheFinalStep(String amount) throws Exception {
        var request = pending(amount); boolean high = new BigDecimal(amount).compareTo(new BigDecimal("1000")) > 0;
        if (!high) limit("2026-10", "3000");
        var response = approve("a:manager", request, 1);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(node(response).path("status").asText()).isEqualTo(high ? "AWAITING_FINANCE_APPROVAL" : "APPROVED");
        assertThat(count("approval_step", request)).isEqualTo(1);
        if (high) {
            assertThat(count("budget_charge", request)).isZero();
            assertThat(owner.queryForObject("SELECT count(*) FROM monthly_budget", Integer.class)).isZero();
            assertThat(node(response).path("approvedAt").isNull()).isTrue();
            assertThat(approve("a:manager", request, 1).statusCode()).isEqualTo(409);
            owner.update("INSERT INTO membership_role VALUES (?, ?, 'FINANCE_APPROVER')", id("a"), id("a:manager:membership"));
            assertThat(approve("a:manager", request, 2).statusCode()).isEqualTo(403);
            var unchanged = effects(request);
            assertThat(approve("a:finance", request, 2).statusCode()).isEqualTo(409);
            assertThat(effects(request)).isEqualTo(unchanged);
            limit("2026-10", "3000"); response = approve("a:finance", request, 2);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
        assertThat(node(response).path("status").asText()).isEqualTo("APPROVED");
        assertThat(node(response).path("budgetMonth").asText()).isEqualTo("2026-10");
        assertThat(node(response).path("approvedAt").asText()).isEqualTo("2026-10-31T23:59:59.999999Z");
        assertThat(used("2026-10")).isEqualByComparingTo(amount);
        assertThat(count("approval_step", request)).isEqualTo(high ? 2 : 1);
        assertThat(count("budget_charge", request)).isEqualTo(1);
        assertThat(count("request_history", request)).isEqualTo(high ? 4 : 3);
        assertThat(count("outbox_event", request)).isEqualTo(high ? 3 : 2);
        var correlation = UUID.fromString(node(response).path("correlationId").asText());
        var instant = OCTOBER.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for (var table : List.of("approval_step", "budget_charge", "request_history", "outbox_event"))
            assertThat(owner.queryForObject("SELECT count(*) FROM " + table + " WHERE request_id = ? AND correlation_id = ? AND occurred_at = ?", Integer.class, request, correlation, Timestamp.from(instant))).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE entity_id = ? AND correlation_id = ? AND occurred_at = ?", Integer.class, request, correlation, Timestamp.from(instant))).isEqualTo(1);
        var recipients = owner.queryForList("SELECT r.identity_id FROM outbox_recipient r JOIN outbox_event e ON e.id = r.event_id WHERE e.request_id = ? AND e.event_type = 'request.approved'", UUID.class, request);
        assertThat(recipients).containsExactlyInAnyOrderElementsOf(high ? List.of(id("a:employee:identity"), id("a:manager:identity")) : List.of(id("a:employee:identity")));
    }

    @Test void commandAuthorityUsesCurrentMembershipAndNeverClientOrTokenRoleClaims() throws Exception {
        var request = pending("700"); limit("2026-10", "1000"); var before = effects(request);
        for (var actor : List.of("a:employee", "a:finance", "a:admin")) assertThat(approve(actor, request, 1).statusCode()).isEqualTo(403);
        assertThat(approve("b:manager", request, 1).statusCode()).isEqualTo(404);
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'DEPARTMENT_MANAGER')", id("a"), id("a:employee:membership"));
        owner.update("INSERT INTO manager_department VALUES (?, ?, ?)", id("a"), id("a:employee:membership"), id("a:engineering"));
        assertThat(approve("a:employee", request, 1).statusCode()).isEqualTo(403);
        owner.update("DELETE FROM manager_department WHERE membership_id = ?", id("a:manager:membership"));
        assertThat(approve("a:manager", request, 1).statusCode()).isEqualTo(403);
        owner.update("INSERT INTO manager_department VALUES (?, ?, ?)", id("a"), id("a:manager:membership"), id("a:engineering"));
        owner.update("DELETE FROM membership_role WHERE membership_id = ?", id("a:manager:membership"));
        assertThat(approve("a:manager", request, 1).statusCode()).isEqualTo(403);
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'DEPARTMENT_MANAGER')", id("a"), id("a:manager:membership"));
        owner.update("UPDATE membership SET active = false WHERE id = ?", id("a:manager:membership"));
        assertThat(approve("a:manager", request, 1).statusCode()).isEqualTo(403);
        assertThat(effects(request)).isEqualTo(before); assertThat(used("2026-10")).isZero();
    }

    @Test void demoSamplesAreRepeatableNeverOverwriteEditsAndExposeTheInsufficientBudgetRoute() throws Exception {
        demoRequests.run(null);
        assertThat(owner.queryForObject("SELECT count(*) FROM purchase_request", Integer.class)).isEqualTo(8);
        assertThat(owner.queryForObject("SELECT count(*) FROM monthly_budget", Integer.class)).isEqualTo(4);
        var sample = id("a:sample:999.99");
        assertThat(send("a:employee", "/api/requests/" + sample, Map.of("version", 0, "title", "Edited fixture", "justification", "Kept after restart", "amount", 700), "PUT", null).statusCode()).isEqualTo(200);
        limit("2026-10", "2000");
        long audits = owner.queryForObject("SELECT count(*) FROM audit_entry", Long.class);
        demo.run(null); demoRequests.run(null);
        assertThat(owner.queryForObject("SELECT amount FROM purchase_request WHERE id = ?", BigDecimal.class, sample)).isEqualByComparingTo("700");
        assertThat(owner.queryForObject("SELECT limit_amount FROM monthly_budget WHERE company_id = ? AND department_id = ? AND budget_month = DATE '2026-10-01'", BigDecimal.class, id("a"), id("a:engineering"))).isEqualByComparingTo("2000");
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry", Long.class)).isEqualTo(audits);
        var insufficient = id("a:sample:6000.00");
        assertThat(send("a:employee", "/api/requests/" + insufficient + "/submit", Map.of("version", 0), "POST", UUID.randomUUID()).statusCode()).isEqualTo(200);
        assertThat(approve("a:manager", insufficient, 1).statusCode()).isEqualTo(200);
        var response = approve("a:finance", insufficient, 2);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(node(response).path("code").asText()).isEqualTo("insufficient_budget");
        assertThat(count("budget_charge", insufficient)).isZero(); assertThat(used("2026-10")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sharedBudgetRaceHasOneWinnerAndUsesFreshUsageAndVersion(boolean finance) throws Exception {
        var one = ready(finance); var two = ready(finance);
        var budget = limit("2026-10", finance ? "2000" : "1000");
        var initialOne = effects(one); var initialTwo = effects(two);
        var results = race("monthly_budget", budget, List.of(() -> finalApprove(finance, one), () -> finalApprove(finance, two)), () -> {});
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        var loser = results.get(0).statusCode() == 409 ? one : two;
        var winner = loser.equals(one) ? two : one;
        assertThat(node(results.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow()).path("code").asText()).isEqualTo("insufficient_budget");
        assertThat(effects(loser)).isEqualTo(loser.equals(one) ? initialOne : initialTwo);
        assertFinal(winner, finance); assertThat(used("2026-10")).isEqualByComparingTo(finance ? "1200" : "700");
        assertThat(owner.queryForObject("SELECT version FROM monthly_budget WHERE id = ?", Long.class, budget)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameRequestDuplicateFinalApprovalsHaveOneCommittedCharge(boolean finance) throws Exception {
        var request = ready(finance); limit("2026-10", "3000");
        var results = race("purchase_request", request, List.of(() -> finalApprove(finance, request), () -> finalApprove(finance, request)), () -> {});
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        assertFinal(request, finance); var before = effects(request);
        assertThat(finalApprove(finance, request).statusCode()).isEqualTo(409);
        assertThat(effects(request)).isEqualTo(before); assertThat(used("2026-10")).isEqualByComparingTo(finance ? "1200" : "700");
    }

    @Test void concurrentDepartmentStepsNeedNoBudgetAndDoNotProduceDuplicateFinanceEntry() throws Exception {
        var request = pending("1200");
        var results = race("purchase_request", request, List.of(() -> approve("a:manager", request, 1), () -> approve("a:manager", request, 1)), () -> {});
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        assertThat(effects(request).status()).isEqualTo("AWAITING_FINANCE_APPROVAL");
        assertThat(count("approval_step", request)).isEqualTo(1); assertThat(count("budget_charge", request)).isZero();
        assertThat(count("request_history", request)).isEqualTo(3); assertThat(count("outbox_event", request)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(*) FROM monthly_budget", Integer.class)).isZero();
    }

    @ParameterizedTest @CsvSource({"false,cancel", "false,reject", "true,cancel", "true,reject"})
    void finalApprovalVersusTerminalActionCommitsOnlyTheWinner(boolean finance, String action) throws Exception {
        var request = ready(finance); limit("2026-10", "3000"); int version = finance ? 2 : 1;
        var terminalActor = action.equals("cancel") ? "a:employee" : finance ? "a:finance" : "a:manager";
        var body = action.equals("cancel") ? Map.<String, Object>of("version", version) : Map.<String, Object>of("version", version, "reason", "Declined");
        var results = race("purchase_request", request, List.of(() -> finalApprove(finance, request), () -> send(terminalActor, "/api/requests/" + request + "/" + action, body, "POST", null)), () -> {});
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        var approved = effects(request).status().equals("APPROVED");
        assertThat(count("budget_charge", request)).isEqualTo(approved ? 1 : 0);
        assertThat(count("approval_step", request)).isEqualTo((finance ? 1 : 0) + (approved ? 1 : 0));
        assertThat(count("request_history", request)).isEqualTo(finance ? 4 : 3);
        assertThat(count("audit_entry", request)).isEqualTo(finance ? 4 : 3);
        assertThat(count("outbox_event", request)).isEqualTo(finance ? 3 : 2);
        assertThat(effects(request).version()).isEqualTo(finance ? 3 : 2);
        assertThat(used("2026-10")).isEqualByComparingTo(approved ? finance ? "1200" : "700" : "0");
        if (!approved) assertThat(effects(request).status()).isEqualTo(action.equals("cancel") ? "CANCELLED" : "REJECTED");
    }

    @Test void budgetLimitReductionAndApprovalUseTheSameLockWithoutLostUpdates() throws Exception {
        var request = pending("700"); var budget = limit("2026-10", "1000");
        var results = race("monthly_budget", budget, List.of(() -> approve("a:manager", request, 1),
                () -> send("a:admin", "/api/admin/departments/" + id("a:engineering") + "/budgets/2026-10", Map.of("limitAmount", 500), "PUT", null)), () -> {});
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        var approved = results.get(0).statusCode() == 200;
        assertThat(node(results.get(approved ? 1 : 0)).path("code").asText()).isEqualTo(approved ? "budget_below_usage" : "insufficient_budget");
        assertThat(used("2026-10")).isEqualByComparingTo(approved ? "700" : "0");
        assertThat(owner.queryForObject("SELECT limit_amount FROM monthly_budget WHERE id = ?", BigDecimal.class, budget)).isEqualByComparingTo(approved ? "1000" : "500");
        assertThat(count("budget_charge", request)).isEqualTo(approved ? 1 : 0);
    }

    @ParameterizedTest @CsvSource({"false,budget_charge", "false,approval_step", "false,request_history", "false,audit_entry", "false,outbox_event", "false,outbox_recipient", "true,budget_charge", "true,approval_step", "true,request_history", "true,audit_entry", "true,outbox_event", "true,outbox_recipient"})
    void everyPostUsageWriteFailureRollsBackJpaAndJdbcIncludingBudgetVersion(boolean finance, String table) throws Exception {
        var request = ready(finance); var budget = limit("2026-10", "3000"); var before = effects(request);
        var budgetBefore = owner.queryForMap("SELECT used_amount, version FROM monthly_budget WHERE id = ?", budget);
        owner.execute("REVOKE INSERT ON " + table + " FROM procureflow_app");
        try { assertThat(finalApprove(finance, request).statusCode()).isEqualTo(500); }
        finally { owner.execute("GRANT INSERT ON " + table + " TO procureflow_app"); }
        assertThat(effects(request)).isEqualTo(before);
        assertThat(owner.queryForMap("SELECT used_amount, version FROM monthly_budget WHERE id = ?", budget)).isEqualTo(budgetBefore);
        assertThat(finalApprove(finance, request).statusCode()).isEqualTo(200); assertFinal(request, finance);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void observerProvesUsageWasFlushedInsideTheFailingChargeTransaction(boolean finance) throws Exception {
        var request = ready(finance); var budget = limit("2026-10", "3000"); var before = effects(request);
        owner.execute("CREATE SEQUENCE flush_observed"); owner.execute("GRANT USAGE ON SEQUENCE flush_observed TO procureflow_app");
        owner.execute("""
                CREATE FUNCTION injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                  IF NOT EXISTS (SELECT 1 FROM monthly_budget WHERE id = NEW.budget_id AND used_amount = NEW.amount AND version = 1) THEN
                    RAISE EXCEPTION 'Usage flush was not observed';
                  END IF;
                  PERFORM nextval('flush_observed');
                  RAISE EXCEPTION 'Injected after observing flushed usage/version';
                END $$
                """);
        owner.execute("CREATE TRIGGER injected_failure BEFORE INSERT ON budget_charge FOR EACH ROW EXECUTE FUNCTION injected_failure()");
        assertThat(finalApprove(finance, request).statusCode()).isEqualTo(500);
        assertThat(owner.queryForObject("SELECT is_called FROM flush_observed", Boolean.class)).isTrue();
        assertThat(effects(request)).isEqualTo(before); assertThat(used("2026-10")).isZero();
        assertThat(owner.queryForObject("SELECT version FROM monthly_budget WHERE id = ?", Long.class, budget)).isZero();
    }

    @Test void commitTimeFailureNeverReturnsSuccessOrLeavesPartialApproval() throws Exception {
        var request = pending("700"); var budget = limit("2026-10", "1000"); var before = effects(request);
        owner.execute("""
                CREATE FUNCTION injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
                  RAISE EXCEPTION 'Injected deferred commit failure' USING ERRCODE = '23514';
                END $$
                """);
        owner.execute("CREATE CONSTRAINT TRIGGER injected_failure AFTER INSERT ON budget_charge DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION injected_failure()");
        assertThat(approve("a:manager", request, 1).statusCode()).isBetween(400, 599);
        assertThat(effects(request)).isEqualTo(before); assertThat(used("2026-10")).isZero();
        assertThat(owner.queryForObject("SELECT version FROM monthly_budget WHERE id = ?", Long.class, budget)).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void utcMonthIsCapturedAfterRequestLockButBeforeBudgetWait(boolean waitOnBudget) throws Exception {
        var request = pending("700"); var octoberBudget = limit("2026-10", "1000"); limit("2026-11", "1000");
        var results = race(waitOnBudget ? "monthly_budget" : "purchase_request", waitOnBudget ? octoberBudget : request,
                List.of(() -> approve("a:manager", request, 1)), () -> clock.current.set(NOVEMBER));
        assertThat(results.getFirst().statusCode()).as(results.getFirst().body()).isEqualTo(200);
        var month = waitOnBudget ? "2026-10" : "2026-11";
        assertThat(node(results.getFirst()).path("budgetMonth").asText()).isEqualTo(month);
        assertThat(used(month)).isEqualByComparingTo("700"); assertThat(used(waitOnBudget ? "2026-11" : "2026-10")).isZero();
    }

    @Test void crossingUtcMonthWithoutNewBudgetLeavesTheRequestPendingWithoutCreatingBudget() throws Exception {
        var request = pending("700"); limit("2026-10", "1000"); clock.current.set(NOVEMBER); var before = effects(request);
        var response = approve("a:manager", request, 1);
        assertThat(response.statusCode()).isEqualTo(409); assertThat(node(response).path("code").asText()).isEqualTo("budget_missing");
        assertThat(effects(request)).isEqualTo(before); assertThat(used("2026-10")).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM monthly_budget", Integer.class)).isEqualTo(1);
    }

    @Test void jpaBudgetLockTimeoutIsRetryableAndMandatoryChargingCannotStartItsOwnTransaction() throws Exception {
        var request = pending("700"); var budget = limit("2026-10", "1000"); var before = effects(request);
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThatThrownBy(() -> charger.charge(id("a"), id("a:engineering"), request, new BigDecimal("700"), 2, OCTOBER, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        try (var lock = connection()) {
            lock.setAutoCommit(false); lock(lock, "monthly_budget", budget);
            var response = approve("a:manager", request, 1);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(node(response).path("code").asText()).isEqualTo("retryable_conflict");
            assertThat(effects(request)).isEqualTo(before); lock.commit();
        }
        assertThat(approve("a:manager", request, 1).statusCode()).isEqualTo(200);
    }

    @Test void legacyP2CommandResponseWithoutAddedFieldsStillReplaysAndCannotBypassAuthorization() throws Exception {
        var key = UUID.randomUUID(); var created = send("a:employee", "/api/requests", content("700"), "POST", key);
        assertThat(created.statusCode()).isEqualTo(201);
        owner.update("UPDATE api_command SET response_json = response_json - 'approvedAt' - 'budgetMonth' WHERE idempotency_key = ?", key);
        var response = send("a:employee", "/api/requests", content("700"), "POST", key);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(node(response).path("id").asText()).isEqualTo(node(created).path("id").asText());
        assertThat(node(response).path("approvedAt").isNull()).isTrue();
        owner.update("UPDATE membership SET active = false WHERE id = ?", id("a:employee:membership"));
        assertThat(send("a:employee", "/api/requests", content("700"), "POST", key).statusCode()).isEqualTo(403);
    }

    @Test void databaseRejectsDuplicateAndCrossCompanyChargesAndApprovalHistoryRewrites() throws Exception {
        var request = pending("700"); limit("2026-10", "1000");
        assertThat(approve("a:manager", request, 1).statusCode()).isEqualTo(200);
        assertSqlState("23505", () -> applicationJdbc.update("""
                INSERT INTO budget_charge SELECT gen_random_uuid(), company_id, request_id, department_id, budget_id,
                budget_month, amount, approval_version, occurred_at, correlation_id FROM budget_charge WHERE request_id = ?
                """, request));
        assertSqlState("23505", () -> applicationJdbc.update("INSERT INTO approval_step SELECT * FROM approval_step WHERE request_id = ?", request));
        for (var table : List.of("approval_step", "budget_charge")) {
            assertSqlState("42501", () -> applicationJdbc.update("UPDATE " + table + " SET company_id = company_id WHERE request_id = ?", request));
            assertSqlState("42501", () -> applicationJdbc.update("DELETE FROM " + table + " WHERE request_id = ?", request));
            assertSqlState("42501", () -> applicationJdbc.execute("TRUNCATE " + table + " CASCADE"));
        }
        var foreignBudget = UUID.randomUUID();
        owner.update("INSERT INTO monthly_budget (id, company_id, department_id, budget_month, limit_amount) VALUES (?, ?, ?, DATE '2026-10-01', 1000)", foreignBudget, id("b"), id("b:engineering"));
        var another = pending("700");
        assertSqlState("23503", () -> applicationJdbc.update("""
                INSERT INTO budget_charge (id, company_id, request_id, department_id, budget_id, budget_month, amount,
                approval_version, occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, DATE '2026-10-01', 700, 2, ?, ?)
                """, UUID.randomUUID(), id("a"), another, id("a:engineering"), foreignBudget, Timestamp.from(OCTOBER), UUID.randomUUID()));
        assertThat(count("budget_charge", request)).isEqualTo(1); assertThat(count("budget_charge", another)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"missing_charge", "wrong_amount", "wrong_month", "history_actor", "history_time"})
    void deferredSettlementRejectsIncompleteOrInconsistentFinalTransactions(String fault) throws Exception {
        var request = pending("700"); var budget = limit("2026-10", "1000");
        var nextBudget = limit("2026-11", "1000"); var before = effects(request); var correlation = UUID.randomUUID();
        var time = OCTOBER.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertSqlState("23514", () -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            applicationJdbc.update("""
                    UPDATE purchase_request SET status = 'APPROVED', version = version + 1, updated_at = ?, approved_at = ?,
                    department_approver_identity_id = ?, department_approver_membership_id = ?, correlation_id = ? WHERE id = ?
                    """, Timestamp.from(time), Timestamp.from(time), id("a:manager:identity"), id("a:manager:membership"), correlation, request);
            applicationJdbc.update("""
                    INSERT INTO approval_step VALUES (?, ?, 'DEPARTMENT', 2, ?, ?, ?, ?)
                    """, id("a"), request, id("a:manager:identity"), id("a:manager:membership"), Timestamp.from(time), correlation);
            var historyActor = fault.equals("history_actor") ? "a:finance" : "a:manager";
            applicationJdbc.update("""
                    INSERT INTO request_history (id, company_id, request_id, request_version, action, from_status, to_status,
                    actor_identity_id, actor_membership_id, correlation_id, occurred_at)
                    VALUES (?, ?, ?, 2, 'request.approved', 'AWAITING_DEPARTMENT_APPROVAL', 'APPROVED', ?, ?, ?, ?)
                    """, UUID.randomUUID(), id("a"), request, id(historyActor + ":identity"), id(historyActor + ":membership"),
                    correlation, Timestamp.from(fault.equals("history_time") ? time.minusSeconds(1) : time));
            if (!fault.equals("missing_charge")) applicationJdbc.update("""
                    INSERT INTO budget_charge VALUES (?, ?, ?, ?, ?, ?, ?, 2, ?, ?)
                    """, UUID.randomUUID(), id("a"), request, id("a:engineering"), fault.equals("wrong_month") ? nextBudget : budget,
                    java.sql.Date.valueOf(fault.equals("wrong_month") ? "2026-11-01" : "2026-10-01"),
                    new BigDecimal(fault.equals("wrong_amount") ? "699" : "700"), Timestamp.from(time), correlation);
            // All statement-time guards passed; validation must reject this completed transaction at commit.
            assertThat(applicationJdbc.queryForObject("SELECT status FROM purchase_request WHERE id = ?", String.class, request)).isEqualTo("APPROVED");
        }));
        assertThat(effects(request)).isEqualTo(before);
        assertThat(used("2026-10")).isZero(); assertThat(used("2026-11")).isZero();
    }

    @Test void databaseRejectsSameActorSecondStepAndAnyChargeBeforeFinalApproval() throws Exception {
        var request = ready(true); var budget = limit("2026-10", "3000"); var before = effects(request);
        var time = OCTOBER.truncatedTo(java.time.temporal.ChronoUnit.MICROS); var correlation = UUID.randomUUID();
        assertSqlState("23514", () -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            applicationJdbc.update("UPDATE purchase_request SET status = 'APPROVED', approved_at = ?, updated_at = ?, correlation_id = ?, version = version + 1 WHERE id = ?",
                    Timestamp.from(time), Timestamp.from(time), correlation, request);
            applicationJdbc.update("INSERT INTO approval_step VALUES (?, ?, 'FINANCE', 3, ?, ?, ?, ?)", id("a"), request,
                    id("a:manager:identity"), id("a:manager:membership"), Timestamp.from(time), correlation);
            fail("The statement-time step guard must reject the prior department actor");
        }));
        assertThat(effects(request)).isEqualTo(before);
        assertSqlState("23514", () -> applicationJdbc.update("""
                INSERT INTO budget_charge VALUES (?, ?, ?, ?, ?, DATE '2026-10-01', 1200, 2, ?, ?)
                """, UUID.randomUUID(), id("a"), request, id("a:engineering"), budget, Timestamp.from(time),
                owner.queryForObject("SELECT correlation_id FROM purchase_request WHERE id = ?", UUID.class, request)));
        assertThat(effects(request)).isEqualTo(before); assertThat(used("2026-10")).isZero();
    }

    @Test void generatedApprovalContractDocumentsBudgetErrorsAndAdditiveFinalizationFields() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var api = node(response); var approve = api.path("paths").path("/api/requests/{id}/approve").path("post");
        assertThat(approve.path("operationId").asText()).isEqualTo("approveRequest");
        assertThat(approve.path("responses").path("409").path("description").asText()).contains("budget_missing", "insufficient_budget", "retryable_conflict");
        assertThat(approve.path("security").toString()).contains("bearerAuth");
        assertThat(approve.path("parameters").toString()).doesNotContain("Idempotency-Key");
        var reference = approve.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asText();
        assertThat(reference).startsWith("#/components/schemas/");
        var fields = api.path("components").path("schemas").path(reference.substring(reference.lastIndexOf('/') + 1)).path("properties");
        assertThat(fields.has("approvedAt")).isTrue(); assertThat(fields.has("budgetMonth")).isTrue();
    }

    private static void assertSqlState(String expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable command) {
        assertThatThrownBy(command).rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo(expected));
    }

    private record Effects(String status, long version, long steps, long charges, long history, long audit, long events, long recipients,
                           Map<String, Object> requestRow) {}
    private Effects effects(UUID request) {
        return new Effects(owner.queryForObject("SELECT status FROM purchase_request WHERE id = ?", String.class, request),
                owner.queryForObject("SELECT version FROM purchase_request WHERE id = ?", Long.class, request),
                count("approval_step", request), count("budget_charge", request), count("request_history", request),
                count("audit_entry", request), count("outbox_event", request),
                owner.queryForObject("SELECT count(*) FROM outbox_recipient r JOIN outbox_event e ON e.id = r.event_id WHERE e.request_id = ?", Long.class, request),
                owner.queryForMap("SELECT * FROM purchase_request WHERE id = ?", request));
    }
    private long count(String table, UUID request) {
        return owner.queryForObject("SELECT count(*) FROM " + table + " WHERE " + (table.equals("audit_entry") ? "entity_id" : "request_id") + " = ?", Long.class, request);
    }
    private BigDecimal used(String month) { return owner.queryForObject("SELECT used_amount FROM monthly_budget WHERE company_id = ? AND department_id = ? AND budget_month = ?", BigDecimal.class, id("a"), id("a:engineering"), java.sql.Date.valueOf(month + "-01")); }
    private void assertFinal(UUID request, boolean finance) {
        assertThat(effects(request)).extracting(Effects::status, Effects::version, Effects::steps, Effects::charges,
                Effects::history, Effects::audit, Effects::events, Effects::recipients)
                .containsExactly("APPROVED", finance ? 3L : 2L, finance ? 2L : 1L, 1L, finance ? 4L : 3L,
                        finance ? 4L : 3L, finance ? 3L : 2L, finance ? 5L : 2L);
    }
    private UUID ready(boolean finance) throws Exception {
        var request = pending(finance ? "1200" : "700");
        if (finance) assertThat(approve("a:manager", request, 1).statusCode()).isEqualTo(200);
        return request;
    }
    private HttpResponse<String> finalApprove(boolean finance, UUID request) throws Exception { return approve(finance ? "a:finance" : "a:manager", request, finance ? 2 : 1); }
    private UUID pending(String amount) throws Exception {
        var response = send("a:employee", "/api/requests", content(amount), "POST", UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var request = UUID.fromString(node(response).path("id").asText());
        assertThat(send("a:employee", "/api/requests/" + request + "/submit", Map.of("version", 0), "POST", UUID.randomUUID()).statusCode()).isEqualTo(200);
        return request;
    }
    private UUID limit(String month, String amount) throws Exception {
        var response = send("a:admin", "/api/admin/departments/" + id("a:engineering") + "/budgets/" + month, Map.of("limitAmount", new BigDecimal(amount)), "PUT", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return UUID.fromString(node(response).path("id").asText());
    }
    private List<HttpResponse<String>> race(String table, UUID target, List<Callable<HttpResponse<String>>> commands, Runnable beforeRelease) throws Exception {
        try (var barrier = connection(); var executor = Executors.newFixedThreadPool(commands.size())) {
            barrier.setAutoCommit(false); lock(barrier, table, target);
            int pid;
            try (var query = barrier.createStatement(); var result = query.executeQuery("SELECT pg_backend_pid()")) { result.next(); pid = result.getInt(1); }
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (var command : commands) futures.add(executor.submit(() -> { start.await(); return command.call(); }));
            start.countDown();
            try { awaitBarrier(pid, commands.size()); beforeRelease.run(); } finally { barrier.commit(); }
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
            return responses;
        }
    }
    private void awaitBarrier(int pid, int expected) throws Exception {
        var deadline = Instant.now().plusSeconds(4); long linked = 0;
        while (Instant.now().isBefore(deadline)) {
            linked = owner.queryForObject("""
                    WITH RECURSIVE chain(pid, seen) AS (
                      SELECT pid, ARRAY[pid] FROM pg_stat_activity WHERE usename = 'procureflow_app' AND wait_event_type = 'Lock'
                      UNION ALL SELECT blocker, chain.seen || blocker FROM chain
                      CROSS JOIN LATERAL unnest(pg_blocking_pids(chain.pid)) blocker WHERE NOT blocker = ANY(chain.seen))
                    SELECT count(DISTINCT seen[1]) FROM chain WHERE pid = ?
                    """, Long.class, pid);
            if (linked >= expected) break;
            Thread.sleep(20);
        }
        assertThat(linked).as("Independent backend PIDs transitively blocked by this exact owner barrier").isEqualTo(expected);
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); }
    private void lock(Connection connection, String table, UUID target) throws SQLException {
        try (var query = connection.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) { query.setObject(1, target); query.executeQuery().close(); }
    }
    private HttpResponse<String> approve(String actor, UUID request, int version) throws Exception { return send(actor, "/api/requests/" + request + "/approve", Map.of("version", version), "POST", null); }
    private Map<String, Object> content(String amount) { return Map.of("title", "Approval fixture", "justification", "Private fixture", "amount", new BigDecimal(amount)); }
    private static JsonNode node(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private HttpResponse<String> send(String actor, String path, Object body, String method, UUID key) throws Exception {
        var token = tokens.computeIfAbsent(actor, name -> TOKENS.token(id(name + ":keycloak").toString()));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key.toString());
        request.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

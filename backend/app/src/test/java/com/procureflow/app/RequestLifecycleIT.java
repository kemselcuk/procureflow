package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.procureflow.app.identity.DemoData.id;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local-demo")
@Import(RequestLifecycleIT.FixedTime.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestLifecycleIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant CLOCK_INSTANT = Instant.parse("2026-10-18T10:15:30.123456789Z");
    static final Instant NOW = CLOCK_INSTANT.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @TestConfiguration static class FixedTime { @Bean @Primary Clock fixedClock() { return Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC); } }
    @LocalServerPort int port;
    @Autowired DemoData demo;
    @Autowired JdbcTemplate applicationJdbc;
    JdbcTemplate owner;
    HttpClient client;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
        properties.add("app.security.issuer", TOKENS::issuer); properties.add("app.security.jwk-set-uri", TOKENS::jwks);
    }
    @BeforeEach void reset() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("TRUNCATE audit_entry, app_identity, company CASCADE"); demo.run(null);
        owner.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, ?, ?)", id("a:other:identity"), TOKENS.issuer(), id("a:other:keycloak").toString());
        owner.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) VALUES (?, ?, ?, ?, true)",
                id("a:other:membership"), id("a"), id("a:other:identity"), id("a:engineering"));
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'EMPLOYEE')", id("a"), id("a:other:membership"));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterEach void closeClient() { if (client != null) client.close(); }
    @AfterAll static void closeTokens() { TOKENS.close(); }

    @Test void canonicalCreateReplayKeepsOriginalResponseAndNeverDuplicatesEffects() throws Exception {
        var key = UUID.randomUUID(); var body = content("50.0");
        var created = send("a:employee", "", "POST", body, key);
        assertThat(created.statusCode()).isEqualTo(201);
        var original = node(created);
        var requestId = uuid(original, "id");
        assertThat(original.path("createdAt").asText()).isEqualTo(NOW.toString());
        assertThat(original.path("departmentId").asText()).isEqualTo(id("a:engineering").toString());
        assertThat(created.headers().firstValue("X-Correlation-ID")).contains(original.path("correlationId").asText());
        owner.update("UPDATE membership SET department_id = ? WHERE id = ?", id("a:operations"), id("a:employee:membership"));
        var replay = send("a:employee", "", "POST", content("50.00"), key);
        assertThat(replay.statusCode()).isEqualTo(201); assertThat(node(replay)).isEqualTo(original);
        assertThat(send("a:employee", "", "POST", content("51"), key).statusCode()).isEqualTo(409);
        assertThat(count("purchase_request")).isEqualTo(1); assertThat(count("request_history")).isEqualTo(1);
        assertThat(count("audit_entry")).isEqualTo(1); assertThat(count("api_command")).isEqualTo(1);
        assertThat(count("outbox_event")).isZero();
        assertThat(send("a:employee", "/" + requestId + "/submit", "POST", Map.of("version", 0), UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(send("a:employee", "/" + requestId, "PUT", edit(0, "55"), null).statusCode()).isEqualTo(200);
        assertThat(node(send("a:employee", "/" + requestId, "GET", null, null)).path("departmentId").asText()).isEqualTo(id("a:operations").toString());
    }

    @Test void staleDraftEditLeavesTheCommittedContentVersionHistoryAndAuditUnchanged() throws Exception {
        var requestId = create("a:employee", "50");
        var edited = send("a:employee", "/" + requestId, "PUT", edit(0, "55"), null);
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(node(edited).path("version").asInt()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT amount FROM purchase_request WHERE id = ?", BigDecimal.class, requestId)).isEqualByComparingTo("55.00");
        var stale = send("a:employee", "/" + requestId, "PUT", edit(0, "55"), null);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(node(stale).path("code").asText()).isEqualTo("stale_request");
        assertThat(node(send("a:employee", "/" + requestId, "GET", null, null))).isEqualTo(node(edited));
        assertThat(count("request_history")).isEqualTo(2); assertThat(count("audit_entry")).isEqualTo(2);
        assertThat(count("outbox_event")).isZero(); assertThat(count("outbox_recipient")).isZero();
        assertThat(count("api_command")).isEqualTo(1);
    }

    @Test void submitCapturesMinimalEventAndCorrelatesStateHistoryAuditAndRecipients() throws Exception {
        var requestId = create("a:employee", "1000.01"); var key = UUID.randomUUID();
        var submitted = submit(requestId, 0, key);
        assertThat(submitted.statusCode()).isEqualTo(200);
        var result = node(submitted);
        assertThat(result.path("status").asText()).isEqualTo("AWAITING_DEPARTMENT_APPROVAL");
        assertThat(result.path("version").asInt()).isEqualTo(1);
        var correlation = uuid(result, "correlationId");
        assertThat(owner.queryForObject("SELECT count(*) FROM request_history WHERE request_id = ? AND correlation_id = ?", Integer.class, requestId, correlation)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE entity_id = ? AND correlation_id = ?", Integer.class, requestId, correlation)).isEqualTo(1);
        var envelope = owner.queryForObject("SELECT envelope::text FROM outbox_event WHERE request_id = ? AND correlation_id = ?", String.class, requestId, correlation);
        var event = JSON.readTree(envelope);
        assertThat(event.path("type").asText()).isEqualTo("request.submitted");
        assertThat(event.path("occurredAt").asText()).isEqualTo(NOW.toString());
        assertThat(owner.queryForObject("SELECT occurred_at FROM outbox_event WHERE request_id = ?", Timestamp.class, requestId).toInstant()).isEqualTo(NOW);
        assertThat(event.path("payload").path("requestVersion").asInt()).isEqualTo(1);
        assertThat(event.path("recipients").size()).isEqualTo(1);
        assertThat(event.path("recipients").get(0).path("identityId").asText()).isEqualTo(id("a:manager:identity").toString());
        assertThat(envelope).doesNotContain("Private justification", "Laptop", "password", "Bearer");
        assertThat(count("outbox_recipient")).isEqualTo(1); assertThat(count("monthly_budget")).isZero();
        owner.update("UPDATE membership SET department_id = ? WHERE id = ?", id("a:operations"), id("a:employee:membership"));
        assertThat(node(submit(requestId, 0, key))).isEqualTo(result);
        assertThat(count("outbox_event")).isEqualTo(1); assertThat(count("request_history")).isEqualTo(2);
        assertThat(send("a:employee", "/" + requestId, "PUT", edit(1, "60"), null).statusCode()).isEqualTo(409);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "0.001", "1000000000000.00"})
    void rejectsInvalidMoneyWithoutRoundingOrCreatingACommandClaim(String amount) throws Exception {
        assertThat(send("a:employee", "", "POST", content(amount), UUID.randomUUID()).statusCode()).isEqualTo(400);
        assertThat(count("purchase_request")).isZero(); assertThat(count("api_command")).isZero();
    }

    @Test void rejectsMissingKeysSpoofedFieldsAndNonEmployeeCreation() throws Exception {
        var missingKey = send("a:employee", "", "POST", content("50"), null);
        assertThat(missingKey.statusCode()).isEqualTo(400);
        assertThat(node(missingKey).path("code").asText()).isEqualTo("invalid_request");
        for (var field : List.of("companyId", "departmentId", "status", "requesterIdentityId")) {
            var body = new HashMap<>(content("50")); body.put(field, id("b"));
            assertThat(send("a:employee", "", "POST", body, UUID.randomUUID()).statusCode()).isEqualTo(400);
        }
        for (var actor : List.of("a:manager", "a:finance", "a:admin"))
            assertThat(send(actor, "", "POST", content("50"), UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(count("purchase_request")).isZero();
    }

    @Test void draftAndSubmittedReadScopesDoNotGiveAdminOrFinanceBlanketAccess() throws Exception {
        var requestId = create("a:employee", "1000.01");
        for (var actor : List.of("a:other", "a:manager", "a:finance", "a:admin"))
            for (var suffix : List.of("", "/history"))
                assertThat(send(actor, "/" + requestId + suffix, "GET", null, null).statusCode()).isEqualTo(403);
        assertThat(submit(requestId, 0, UUID.randomUUID()).statusCode()).isEqualTo(200);
        assertThat(send("a:manager", "/" + requestId, "GET", null, null).statusCode()).isEqualTo(200);
        assertThat(send("a:finance", "/" + requestId, "GET", null, null).statusCode()).isEqualTo(403);
        for (var suffix : List.of("", "/history"))
            assertThat(send("b:employee", "/" + requestId + suffix, "GET", null, null).statusCode()).isEqualTo(404);
        for (var action : List.of("submit", "cancel"))
            assertThat(send("b:employee", "/" + requestId + "/" + action, "POST", Map.of("version", 1), UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(send("b:manager", "/" + requestId + "/reject", "POST", Map.of("version", 1, "reason", "Denied"), null).statusCode()).isEqualTo(404);
        assertThat(send("a:other", "/" + requestId + "/cancel", "POST", Map.of("version", 1), null).statusCode()).isEqualTo(403);
        assertThat(send("a:admin", "/" + requestId + "/reject", "POST", Map.of("version", 1, "reason", "Denied"), null).statusCode()).isEqualTo(403);
    }

    @Test void historyContainsMetadataNotPrivateDraftSnapshotsAndPaginationIsScoped() throws Exception {
        var first = create("a:employee", "50"); var second = create("a:employee", "60"); create("b:employee", "70");
        assertThat(send("a:employee", "/" + first, "PUT", edit(0, "55"), null).statusCode()).isEqualTo(200);
        assertThat(submit(first, 1, UUID.randomUUID()).statusCode()).isEqualTo(200);
        var history = send("a:manager", "/" + first + "/history", "GET", null, null);
        assertThat(node(history).size()).isEqualTo(3);
        assertThat(history.body()).doesNotContain("Private justification", "Laptop", "amount");
        for (int i = 0; i < 3; i++) assertThat(node(history).get(i).path("version").asInt()).isEqualTo(i);
        var page0 = node(send("a:employee", "?page=0&size=1", "GET", null, null));
        var page1 = node(send("a:employee", "?page=1&size=1", "GET", null, null));
        assertThat(page0.path("total").asInt()).isEqualTo(2);
        assertThat(page0.path("items").get(0).path("id").asText()).isNotEqualTo(page1.path("items").get(0).path("id").asText());
        var inbox = node(send("a:manager", "?view=inbox", "GET", null, null));
        assertThat(inbox.path("total").asInt()).isEqualTo(1);
        assertThat(inbox.path("items").get(0).path("id").asText()).isEqualTo(first.toString());
        assertThat(inbox.toString()).doesNotContain(second.toString());
        assertThat(send("a:employee", "?view=inbox", "GET", null, null).statusCode()).isEqualTo(403);
        assertThat(send("a:employee", "?size=101", "GET", null, null).statusCode()).isEqualTo(400);
    }

    @Test void cancellationAndRejectionAreTerminalVersionedAndNotifyThePreviousQueue() throws Exception {
        var requestId = create("a:employee", "50");
        assertThat(send("a:employee", "/" + requestId + "/cancel", "POST", Map.of("version", 0), null).statusCode()).isEqualTo(409);
        submit(requestId, 0, UUID.randomUUID());
        assertThat(send("a:employee", "/" + requestId + "/reject", "POST", Map.of("version", 1, "reason", "No"), null).statusCode()).isEqualTo(403);
        var cancelled = send("a:employee", "/" + requestId + "/cancel", "POST", Map.of("version", 1), null);
        assertThat(cancelled.statusCode()).isEqualTo(200); assertThat(node(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(send("a:employee", "/" + requestId + "/cancel", "POST", Map.of("version", 1), null).statusCode()).isEqualTo(409);
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_recipient r JOIN outbox_event e ON e.id = r.event_id WHERE e.request_id = ? AND e.event_type = 'request.cancelled' AND r.identity_id = ?", Integer.class, requestId, id("a:manager:identity"))).isEqualTo(1);
        var another = create("a:employee", "60"); submit(another, 0, UUID.randomUUID());
        assertThat(send("a:manager", "/" + another + "/reject", "POST", Map.of("version", 1, "reason", "  "), null).statusCode()).isEqualTo(400);
        var rejected = send("a:manager", "/" + another + "/reject", "POST", Map.of("version", 1, "reason", "Not needed"), null);
        assertThat(rejected.statusCode()).isEqualTo(200); assertThat(node(rejected).path("status").asText()).isEqualTo("REJECTED");
        assertThat(owner.queryForObject("SELECT reason FROM request_history WHERE request_id = ? AND request_version = 2", String.class, another)).isEqualTo("Not needed");
    }

    @Test void stalePendingRejectionAndCompletedRejectionRetryNeverDuplicateEffects() throws Exception {
        var requestId = create("a:employee", "50");
        var pending = submit(requestId, 0, UUID.randomUUID());
        assertThat(pending.statusCode()).isEqualTo(200);
        var stale = send("a:manager", "/" + requestId + "/reject", "POST", Map.of("version", 0, "reason", "Declined"), null);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(node(stale).path("code").asText()).isEqualTo("stale_request");
        assertThat(node(send("a:employee", "/" + requestId, "GET", null, null))).isEqualTo(node(pending));
        assertThat(count("request_history")).isEqualTo(2); assertThat(count("audit_entry")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(1); assertThat(count("outbox_recipient")).isEqualTo(1);
        var rejected = send("a:manager", "/" + requestId + "/reject", "POST", Map.of("version", 1, "reason", "Declined"), null);
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(node(rejected).path("status").asText()).isEqualTo("REJECTED");
        assertThat(node(rejected).path("version").asInt()).isEqualTo(2);
        assertThat(count("request_history")).isEqualTo(3); assertThat(count("audit_entry")).isEqualTo(3);
        assertThat(count("outbox_event")).isEqualTo(2); assertThat(count("outbox_recipient")).isEqualTo(2);
        var retry = send("a:manager", "/" + requestId + "/reject", "POST", Map.of("version", 1, "reason", "Declined"), null);
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(node(retry).path("code").asText()).isEqualTo("invalid_request_state");
        assertThat(node(send("a:employee", "/" + requestId, "GET", null, null))).isEqualTo(node(rejected));
        assertThat(count("request_history")).isEqualTo(3); assertThat(count("audit_entry")).isEqualTo(3);
        assertThat(count("outbox_event")).isEqualTo(2); assertThat(count("outbox_recipient")).isEqualTo(2);
        assertThat(count("api_command")).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT reason FROM request_history WHERE request_id = ? AND request_version = 2", String.class, requestId)).isEqualTo("Declined");
    }

    @ParameterizedTest @ValueSource(strings = {"request_history", "audit_entry", "outbox_event", "outbox_recipient"})
    void downstreamFailureRollsBackStateHistoryAuditEventRecipientsAndTheRetryClaim(String deniedTable) throws Exception {
        var requestId = create("a:employee", "50"); var key = UUID.randomUUID();
        owner.execute("REVOKE INSERT ON " + deniedTable + " FROM procureflow_app");
        try { assertThat(submit(requestId, 0, key).statusCode()).isEqualTo(500); }
        finally { owner.execute("GRANT INSERT ON " + deniedTable + " TO procureflow_app"); }
        var unchanged = node(send("a:employee", "/" + requestId, "GET", null, null));
        assertThat(unchanged.path("status").asText()).isEqualTo("DRAFT"); assertThat(unchanged.path("version").asInt()).isZero();
        assertThat(count("request_history")).isEqualTo(1); assertThat(count("audit_entry")).isEqualTo(1);
        assertThat(count("api_command")).isEqualTo(1); assertThat(count("outbox_event")).isZero(); assertThat(count("outbox_recipient")).isZero();
        assertThat(submit(requestId, 0, key).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void realDatabaseBarrierProvesSameKeyReplayAndDifferentKeySubmitConflict(boolean sameKey) throws Exception {
        var requestId = create("a:employee", "50"); var firstKey = UUID.randomUUID();
        try (var lock = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var executor = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM purchase_request WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, requestId); statement.executeQuery().close();
            }
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return submit(requestId, 0, firstKey); });
            var second = executor.submit(() -> { start.await(); return submit(requestId, 0, sameKey ? firstKey : UUID.randomUUID()); });
            start.countDown();
            try {
                var deadline = Instant.now().plusSeconds(4); long waiting = 0;
                while (Instant.now().isBefore(deadline)) {
                    waiting = owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename = 'procureflow_app' AND wait_event_type = 'Lock' AND (query LIKE '%purchase_request%' OR query LIKE '%api_command%')", Long.class);
                    if (waiting >= 2) break;
                    Thread.sleep(20);
                }
                assertThat(waiting).as("Both independent HTTP transactions reached database lock barriers").isGreaterThanOrEqualTo(2);
            } finally { lock.commit(); }
            var one = first.get(15, TimeUnit.SECONDS); var two = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(one.statusCode(), two.statusCode())).containsExactlyInAnyOrder(200, sameKey ? 200 : 409);
            if (sameKey) assertThat(node(one)).isEqualTo(node(two));
            assertThat(count("outbox_event")).isEqualTo(1); assertThat(count("request_history")).isEqualTo(2);
            assertThat(count("api_command")).isEqualTo(2);
        }
    }

    @Test void replayNeverBypassesCurrentRoleOrMembershipRevocation() throws Exception {
        var key = UUID.randomUUID(); assertThat(send("a:employee", "", "POST", content("50"), key).statusCode()).isEqualTo(201);
        owner.update("DELETE FROM membership_role WHERE membership_id = ?", id("a:employee:membership"));
        assertThat(send("a:employee", "", "POST", content("50"), key).statusCode()).isEqualTo(403);
        owner.update("UPDATE membership SET active = false WHERE id = ?", id("a:employee:membership"));
        assertThat(send("a:employee", "", "GET", null, null).statusCode()).isEqualTo(403);
    }

    @Test void financeRejectsOnlyItsRealStepAndCannotReuseTheDepartmentActor() throws Exception {
        var requestId = create("a:employee", "1000.01"); submit(requestId, 0, UUID.randomUUID());
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'FINANCE_APPROVER')", id("a"), id("a:manager:membership"));
        financeEntryFixture(requestId);
        assertThat(send("a:finance", "/" + requestId, "GET", null, null).statusCode()).isEqualTo(200);
        assertThat(send("a:manager", "/" + requestId + "/reject", "POST", Map.of("version", 2, "reason", "Same actor"), null).statusCode()).isEqualTo(403);
        assertThat(node(send("a:manager", "?view=inbox", "GET", null, null)).path("total").asInt()).isZero();
        assertThat(node(send("a:finance", "?view=inbox", "GET", null, null)).path("total").asInt()).isEqualTo(1);
        assertThat(send("a:finance", "/" + requestId + "/reject", "POST", Map.of("version", 2, "reason", "Finance declined"), null).statusCode()).isEqualTo(200);
        var recipients = owner.queryForList("SELECT r.identity_id FROM outbox_recipient r JOIN outbox_event e ON e.id = r.event_id WHERE e.request_id = ? AND e.event_type = 'request.rejected'", UUID.class, requestId);
        assertThat(recipients).containsExactlyInAnyOrder(id("a:employee:identity"), id("a:manager:identity"));
    }

    @Test void ownerCanCancelAtFinanceAndNotifyPriorApproverAndThePreviousFinanceQueue() throws Exception {
        var requestId = create("a:employee", "1000.01");
        assertThat(submit(requestId, 0, UUID.randomUUID()).statusCode()).isEqualTo(200);
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'FINANCE_APPROVER')", id("a"), id("a:manager:membership"));
        financeEntryFixture(requestId);
        var cancelled = send("a:employee", "/" + requestId + "/cancel", "POST", Map.of("version", 2), null);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(node(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(node(cancelled).path("version").asInt()).isEqualTo(3);
        assertThat(node(send("a:employee", "/" + requestId, "GET", null, null))).isEqualTo(node(cancelled));
        assertThat(count("request_history")).isEqualTo(4); assertThat(count("audit_entry")).isEqualTo(3);
        assertThat(count("outbox_event")).isEqualTo(2); assertThat(count("outbox_recipient")).isEqualTo(3);
        assertThat(count("api_command")).isEqualTo(2); assertThat(count("monthly_budget")).isZero();
        var recipients = owner.queryForList("SELECT r.identity_id FROM outbox_recipient r JOIN outbox_event e ON e.id = r.event_id WHERE e.request_id = ? AND e.event_type = 'request.cancelled'", UUID.class, requestId);
        assertThat(recipients).containsExactlyInAnyOrder(id("a:manager:identity"), id("a:finance:identity"));
        var correlation = uuid(node(cancelled), "correlationId");
        assertThat(owner.queryForObject("SELECT count(*) FROM request_history WHERE request_id = ? AND request_version = 3 AND from_status = 'AWAITING_FINANCE_APPROVAL' AND to_status = 'CANCELLED' AND correlation_id = ?", Integer.class, requestId, correlation)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE entity_id = ? AND action = 'request.cancelled' AND correlation_id = ?", Integer.class, requestId, correlation)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_event WHERE request_id = ? AND event_type = 'request.cancelled' AND correlation_id = ?", Integer.class, requestId, correlation)).isEqualTo(1);
        assertThat(send("a:employee", "/" + requestId + "/cancel", "POST", Map.of("version", 2), null).statusCode()).isEqualTo(409);
        assertThat(count("request_history")).isEqualTo(4); assertThat(count("audit_entry")).isEqualTo(3);
        assertThat(count("outbox_event")).isEqualTo(2); assertThat(count("outbox_recipient")).isEqualTo(3);
    }

    @Test void noEligibleManagerStillRecordsTheEventWithoutInventingRecipients() throws Exception {
        owner.update("UPDATE membership SET active = false WHERE id = ?", id("a:manager:membership"));
        var requestId = create("a:employee", "50");
        assertThat(submit(requestId, 0, UUID.randomUUID()).statusCode()).isEqualTo(200);
        assertThat(count("outbox_event")).isEqualTo(1); assertThat(count("outbox_recipient")).isZero();
        var event = JSON.readTree(owner.queryForObject("SELECT envelope::text FROM outbox_event", String.class));
        assertThat(event.path("recipients").size()).isZero();
    }

    @Test void requestLockTimeoutIsRetryableWithTheSameKeyAndDoesNotLeaveAClaim() throws Exception {
        var requestId = create("a:employee", "50"); var key = UUID.randomUUID();
        try (var lock = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM purchase_request WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, requestId); statement.executeQuery().close();
            }
            var response = submit(requestId, 0, key);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(node(response).path("code").asText()).isEqualTo("retryable_conflict");
            assertThat(count("api_command")).isEqualTo(1);
            lock.commit();
        }
        assertThat(submit(requestId, 0, key).statusCode()).isEqualTo(200);
    }

    @Test void concurrentCancelAndRejectCommitOnlyOneTerminalTransition() throws Exception {
        var requestId = create("a:employee", "50"); submit(requestId, 0, UUID.randomUUID());
        try (var lock = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var executor = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM purchase_request WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, requestId); statement.executeQuery().close();
            }
            var start = new CountDownLatch(1);
            var cancel = executor.submit(() -> { start.await(); return send("a:employee", "/" + requestId + "/cancel", "POST", Map.of("version", 1), null); });
            var reject = executor.submit(() -> { start.await(); return send("a:manager", "/" + requestId + "/reject", "POST", Map.of("version", 1, "reason", "Declined"), null); });
            start.countDown();
            try {
                var deadline = Instant.now().plusSeconds(4); long waiting = 0;
                while (Instant.now().isBefore(deadline)) {
                    waiting = owner.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename = 'procureflow_app' AND wait_event_type = 'Lock' AND query LIKE '%purchase_request%'", Long.class);
                    if (waiting >= 2) break;
                    Thread.sleep(20);
                }
                assertThat(waiting).isGreaterThanOrEqualTo(2);
            } finally { lock.commit(); }
            assertThat(List.of(cancel.get(15, TimeUnit.SECONDS).statusCode(), reject.get(15, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200, 409);
            assertThat(count("request_history")).isEqualTo(3); assertThat(count("outbox_event")).isEqualTo(2);
            assertThat(owner.queryForObject("SELECT status FROM purchase_request WHERE id = ?", String.class, requestId)).isIn("CANCELLED", "REJECTED");
        }
    }

    @Test void databaseRejectsUnfinishedClaimsAndSubmittedContentChangesAndHistoryRewrites() throws Exception {
        var requestId = create("a:employee", "50"); submit(requestId, 0, UUID.randomUUID());
        assertThatThrownBy(() -> applicationJdbc.update("UPDATE purchase_request SET title = 'forged', version = version + 1 WHERE id = ?", requestId))
                .rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(() -> applicationJdbc.update("DELETE FROM request_history WHERE request_id = ?", requestId))
                .rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO api_command (company_id, actor_identity_id, idempotency_key, operation, fingerprint, created_at) VALUES (?, ?, ?, 'CREATE', ?, now())",
                id("a"), id("a:employee:identity"), UUID.randomUUID(), "0".repeat(64)))
                .rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        assertThat(count("api_command")).isEqualTo(2);
    }

    @Test void postgresRejectsCrossCompanyRequestHistoryAndRecipientRelationships() throws Exception {
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id, title, justification, amount, created_at, updated_at, correlation_id) VALUES (?, ?, ?, ?, ?, 'Invalid', 'Invalid', 10, now(), now(), ?)",
                UUID.randomUUID(), id("a"), id("b:employee:identity"), id("b:employee:membership"), id("a:engineering"), UUID.randomUUID()))
                .rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
        var requestId = create("a:employee", "50"); var correlation = UUID.randomUUID();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(applicationJdbc.getDataSource())));
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            applicationJdbc.update("UPDATE purchase_request SET status = 'AWAITING_DEPARTMENT_APPROVAL', submitted_at = ?, version = version + 1, correlation_id = ? WHERE id = ?",
                    Timestamp.from(NOW), correlation, requestId);
            applicationJdbc.update("INSERT INTO request_history (id, company_id, request_id, request_version, action, from_status, to_status, actor_identity_id, actor_membership_id, correlation_id, occurred_at) VALUES (?, ?, ?, 1, 'request.submitted', 'DRAFT', 'AWAITING_DEPARTMENT_APPROVAL', ?, ?, ?, ?)",
                    UUID.randomUUID(), id("a"), requestId, id("b:employee:identity"), id("a:employee:membership"), correlation, Timestamp.from(NOW));
        })).rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
        assertThat(node(send("a:employee", "/" + requestId, "GET", null, null)).path("status").asText()).isEqualTo("DRAFT");
        assertThat(submit(requestId, 0, UUID.randomUUID()).statusCode()).isEqualTo(200);
        var eventId = owner.queryForObject("SELECT id FROM outbox_event WHERE request_id = ?", UUID.class, requestId);
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                id("a"), eventId, id("b:employee:identity"), id("b:employee:membership")))
                .rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                id("b"), eventId, id("b:employee:identity"), id("b:employee:membership")))
                .rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
        assertThat(count("outbox_recipient")).isEqualTo(1);
        assertThatThrownBy(() -> applicationJdbc.update("INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, occurred_at, envelope) VALUES (?, ?, ?, 'request.submitted', 1, ?, now(), '{}'::jsonb)",
                UUID.randomUUID(), id("a"), requestId, UUID.randomUUID()))
                .rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @Test void currentDepartmentAssignmentAndOwnershipApplyToReadsAndEveryMutation() throws Exception {
        var requestId = create("a:employee", "50");
        assertThat(send("a:other", "/" + requestId, "PUT", edit(0, "60"), null).statusCode()).isEqualTo(403);
        assertThat(send("a:other", "/" + requestId + "/submit", "POST", Map.of("version", 0), UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(send("b:employee", "/" + requestId, "PUT", edit(0, "60"), null).statusCode()).isEqualTo(404);
        submit(requestId, 0, UUID.randomUUID());
        owner.update("DELETE FROM manager_department WHERE membership_id = ?", id("a:manager:membership"));
        owner.update("INSERT INTO manager_department VALUES (?, ?, ?)", id("a"), id("a:manager:membership"), id("a:operations"));
        assertThat(send("a:manager", "/" + requestId, "GET", null, null).statusCode()).isEqualTo(403);
        assertThat(send("a:manager", "/" + requestId + "/history", "GET", null, null).statusCode()).isEqualTo(403);
        assertThat(send("a:manager", "/" + requestId + "/reject", "POST", Map.of("version", 1, "reason", "Wrong department"), null).statusCode()).isEqualTo(403);
        assertThat(node(send("a:manager", "?view=inbox", "GET", null, null)).path("total").asInt()).isZero();
    }

    @Test void allowedCorsPreflightIncludesTheRequiredIdempotencyHeader() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/requests"))
                .header("Origin", "http://localhost:4200").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase()).contains("idempotency-key");
    }

    @Test void generatedOpenApiExposesTheActualCreateStatusKeyAndBearerContract() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var api = node(response); var create = api.path("paths").path("/api/requests").path("post");
        assertThat(create.path("operationId").asText()).isEqualTo("createRequest");
        assertThat(create.path("responses").path("201").isMissingNode()).isFalse();
        assertThat(create.path("responses").path("409").path("description").asText()).contains("idempotency_key_reused");
        assertThat(create.path("security").toString()).contains("bearerAuth");
        boolean requiredUuidKey = false;
        for (var parameter : create.path("parameters")) {
            if (parameter.path("name").asText().equals("Idempotency-Key"))
                requiredUuidKey = parameter.path("required").asBoolean() && parameter.path("schema").path("format").asText().equals("uuid");
        }
        assertThat(requiredUuidKey).isTrue();
        assertThat(api.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asText()).isEqualTo("bearer");
    }

    private void financeEntryFixture(UUID requestId) {
        // P3 approval is not implemented: this owner-only fixture establishes state/history, not its audit/event effects.
        var correlation = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(owner.getDataSource()))).executeWithoutResult(ignored -> {
            owner.update("UPDATE purchase_request SET status = 'AWAITING_FINANCE_APPROVAL', version = version + 1, department_approver_identity_id = ?, department_approver_membership_id = ?, correlation_id = ? WHERE id = ?",
                    id("a:manager:identity"), id("a:manager:membership"), correlation, requestId);
            owner.update("INSERT INTO request_history (id, company_id, request_id, request_version, action, from_status, to_status, actor_identity_id, actor_membership_id, correlation_id, occurred_at) VALUES (?, ?, ?, 2, 'request.department_approved', 'AWAITING_DEPARTMENT_APPROVAL', 'AWAITING_FINANCE_APPROVAL', ?, ?, ?, ?)",
                    UUID.randomUUID(), id("a"), requestId, id("a:manager:identity"), id("a:manager:membership"), correlation, Timestamp.from(NOW));
        });
    }
    private UUID create(String actor, String amount) throws Exception {
        var response = send(actor, "", "POST", content(amount), UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return uuid(node(response), "id");
    }
    private HttpResponse<String> submit(UUID requestId, int version, UUID key) throws Exception {
        return send("a:employee", "/" + requestId + "/submit", "POST", Map.of("version", version), key);
    }
    private Map<String, Object> content(String amount) { return Map.of("title", "Laptop", "justification", "Private justification", "amount", new BigDecimal(amount)); }
    private Map<String, Object> edit(int version, String amount) { var body = new HashMap<>(content(amount)); body.put("version", version); return body; }
    private long count(String table) { return owner.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private static JsonNode node(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private static UUID uuid(JsonNode node, String key) { return UUID.fromString(node.path(key).asText()); }
    private HttpResponse<String> send(String actor, String suffix, String method, Object body, UUID key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/requests" + suffix)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + TOKENS.token(id(actor + ":keycloak").toString()));
        if (key != null) request.header("Idempotency-Key", key.toString());
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

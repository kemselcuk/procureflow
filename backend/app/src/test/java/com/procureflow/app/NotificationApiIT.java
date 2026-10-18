package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/**
 * Real-HTTP acceptance for the P4 own-notification API and the company-admin backlog API against
 * real PostgreSQL and signed JWT fixtures. Canonical event/recipient rows are owner-only setup, and
 * delivery effects are inserted through the actual worker login; that is API authorization
 * evidence, not proof of message consumption.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationApiIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-10-18T09:00:00.123456Z");
    static final Instant EARLIER = NOW.minusSeconds(3600);
    @LocalServerPort int port;
    @Autowired DemoData demo;
    JdbcTemplate owner;
    JdbcTemplate worker;
    HttpClient client;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
        properties.add("app.security.issuer", TOKENS::issuer);
        properties.add("app.security.jwk-set-uri", TOKENS::jwks);
    }

    @BeforeEach void reset() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        worker = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "procureflow_worker", TestDatabase.workerPassword()));
        owner.execute("""
                TRUNCATE notification_diagnostic, email_job, in_app_notification, notification_delivery, notification_inbox,
                    outbox_dispatch, outbox_recipient, outbox_event, request_history, api_command, budget_charge,
                    approval_step, audit_entry, manager_department, membership_role, monthly_budget, membership,
                    department, app_identity, company CASCADE
                """);
        demo.run(null);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void closeClient() { if (client != null) client.close(); }
    @AfterAll static void closeTokens() { TOKENS.close(); }

    @Test void requiresAValidlySignedBearerToken() throws Exception {
        var notification = seed("a", "request.submitted", NOW, Map.of("a:employee", EARLIER)).notifications().get("a:employee");
        var forged = TOKENS.token(id("a:employee:keycloak").toString(), ignored -> {}, true);
        assertThat(sendToken(null, "GET", "/api/notifications", null).statusCode()).isEqualTo(401);
        assertThat(sendToken(forged, "GET", "/api/notifications", null).statusCode()).isEqualTo(401);
        assertThat(sendToken(null, "POST", "/api/notifications/" + notification + "/read", null).statusCode()).isEqualTo(401);
        assertThat(sendToken(forged, "POST", "/api/notifications/" + notification + "/read", null).statusCode()).isEqualTo(401);
        assertThat(sendToken(null, "GET", "/api/admin/notifications/backlog", null).statusCode()).isEqualTo(401);
        assertThat(persistedReadAt(notification)).isNull();
    }

    @Test void inactiveMembershipIsForbiddenForListAndRead() throws Exception {
        var notification = seed("a", "request.submitted", NOW, Map.of("a:employee", EARLIER)).notifications().get("a:employee");
        owner.update("UPDATE membership SET active = false WHERE id = ?", id("a:employee:membership"));
        var list = list("a:employee");
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(nodes(list).path("code").asText()).isEqualTo("forbidden");
        assertThat(read("a:employee", notification).statusCode()).isEqualTo(403);
        assertThat(persistedReadAt(notification)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"employee", "manager", "finance", "admin"})
    void everyActiveRoleListsAndReadsOnlyItsOwnNotification(String role) throws Exception {
        var actor = "a:" + role;
        var mine = seed("a", "request.submitted", NOW, Map.of(actor, EARLIER));
        var foreign = seed("b", "request.submitted", NOW, Map.of("b:" + role, EARLIER));
        var list = list(actor);
        assertThat(list.statusCode()).isEqualTo(200);
        var body = nodes(list);
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(20);
        assertThat(body.path("total").asLong()).isEqualTo(1);
        assertThat(body.path("items").size()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("id").asText()).isEqualTo(mine.notifications().get(actor).toString());
        assertThat(list.body()).doesNotContain(foreign.notifications().get("b:" + role).toString(), id("b").toString());
        var read = read(actor, mine.notifications().get(actor));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(nodes(read).path("id").asText()).isEqualTo(mine.notifications().get(actor).toString());
        assertThat(nodes(read).path("readAt").isNull()).isFalse();
    }

    @Test void foreignOtherOwnerUnknownIdsAndSpoofedScopeHeadersAreDenied() throws Exception {
        var mine = seed("a", "request.submitted", NOW, Map.of("a:employee", EARLIER));
        var foreign = seed("b", "request.submitted", NOW, Map.of("b:employee", EARLIER));
        assertThat(read("a:employee", foreign.notifications().get("b:employee")).statusCode()).isEqualTo(404);
        assertThat(read("a:manager", mine.notifications().get("a:employee")).statusCode()).isEqualTo(404);
        assertThat(read("a:employee", UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(persistedReadAt(mine.notifications().get("a:employee"))).isNull();
        assertThat(persistedReadAt(foreign.notifications().get("b:employee"))).isNull();

        var spoofed = HttpRequest.newBuilder(url("/api/notifications"))
                .header("Authorization", "Bearer " + token("a:employee"))
                .header("X-Company-Id", id("b").toString()).header("X-Identity-Id", id("b:employee:identity").toString())
                .GET().build();
        var response = client.send(spoofed, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(nodes(response).path("total").asLong()).isEqualTo(1);
        assertThat(response.body()).doesNotContain(foreign.notifications().get("b:employee").toString());
        // Another owner in the same company sees none of this employee's rows either.
        assertThat(nodes(list("a:manager")).path("total").asLong()).isZero();
    }

    @Test void readPersistsTheFirstTimestampUnderConcurrentHttpRetries() throws Exception {
        var notification = seed("a", "request.submitted", NOW, Map.of("a:employee", EARLIER)).notifications().get("a:employee");
        try (var executor = Executors.newFixedThreadPool(2);
             var lock = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM in_app_notification WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, notification);
                statement.executeQuery().close();
            }
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return read("a:employee", notification); });
            var second = executor.submit(() -> { start.await(); return read("a:employee", notification); });
            start.countDown();
            boolean released = false;
            try {
                awaitBlockedReadUpdates(2);
                lock.commit();
                released = true;
                var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
                assertThat(responses).allSatisfy(response ->
                        assertThat(response.statusCode()).as(response.body()).isEqualTo(200));
                var readAt = nodes(responses.getFirst()).path("readAt").asText();
                assertThat(readAt).isNotBlank();
                assertThat(nodes(responses.getLast()).path("readAt").asText()).isEqualTo(readAt);
                assertThat(Instant.parse(readAt)).isEqualTo(persistedReadAt(notification));
                var retry = read("a:employee", notification);
                assertThat(retry.statusCode()).isEqualTo(200);
                assertThat(nodes(retry).path("readAt").asText()).isEqualTo(readAt);
                assertThat(persistedReadAt(notification)).isEqualTo(Instant.parse(readAt));
            } finally {
                if (!released) lock.rollback();
                first.cancel(true);
                second.cancel(true);
            }
        }
    }

    @Test void roleAndDepartmentChangesKeepOwnNotificationAccess() throws Exception {
        var notification = seed("a", "request.submitted", NOW, Map.of("a:employee", EARLIER)).notifications().get("a:employee");
        var path = "/api/admin/memberships/" + id("a:employee:membership");
        var moved = send("a:admin", "PUT", path, Map.of("departmentId", id("a:operations"), "active", true,
                "version", 0L, "roles", List.of("EMPLOYEE"), "managedDepartmentIds", List.of()));
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(nodes(list("a:employee")).path("total").asLong()).isEqualTo(1);

        var promoted = send("a:admin", "PUT", path, Map.of("departmentId", id("a:operations"), "active", true,
                "version", 1L, "roles", List.of("DEPARTMENT_MANAGER"), "managedDepartmentIds", List.of(id("a:operations"))));
        assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(200);
        assertThat(nodes(list("a:employee")).path("items").get(0).path("id").asText()).isEqualTo(notification.toString());
        assertThat(read("a:employee", notification).statusCode()).isEqualTo(200);
        assertThat(persistedReadAt(notification)).isNotNull();
    }

    @Test void paginationIsStableWhenCreatedAtTies() throws Exception {
        var first = seed("a", "request.submitted", NOW.minusSeconds(10), Map.of("a:employee", NOW)).notifications().get("a:employee");
        var second = seed("a", "request.department_approved", NOW.minusSeconds(20), Map.of("a:employee", NOW)).notifications().get("a:employee");
        var third = seed("a", "request.rejected", NOW.minusSeconds(30), Map.of("a:employee", NOW)).notifications().get("a:employee");
        var latest = seed("a", "request.cancelled", NOW.minusSeconds(5), Map.of("a:employee", NOW.plusSeconds(60))).notifications().get("a:employee");
        // PostgreSQL orders UUIDs byte-wise, which is the canonical lowercase hex string order.
        var tied = java.util.stream.Stream.of(first, second, third)
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        var page0 = nodes(send("a:employee", "GET", "/api/notifications?page=0&size=2", null));
        assertThat(page0.path("total").asLong()).isEqualTo(4);
        assertThat(ids(page0.path("items"))).containsExactly(latest, tied.get(0));
        var page1 = nodes(send("a:employee", "GET", "/api/notifications?page=1&size=2", null));
        assertThat(page1.path("total").asLong()).isEqualTo(4);
        assertThat(ids(page1.path("items"))).containsExactly(tied.get(1), tied.get(2));
        var page2 = nodes(send("a:employee", "GET", "/api/notifications?page=2&size=2", null));
        assertThat(page2.path("items").size()).isZero();
        var union = new HashSet<>(ids(page0.path("items")));
        union.addAll(ids(page1.path("items")));
        assertThat(union).containsExactlyInAnyOrder(latest, first, second, third);
    }

    @Test void emptyScopeHugeOffsetAndInvalidPagination() throws Exception {
        var empty = nodes(list("a:employee"));
        assertThat(empty.path("items").isArray()).isTrue();
        assertThat(empty.path("items").size()).isZero();
        assertThat(empty.path("total").asLong()).isZero();

        seed("a", "request.submitted", NOW, Map.of("a:employee", EARLIER));
        var huge = nodes(send("a:employee", "GET", "/api/notifications?page=1000000&size=100", null));
        assertThat(huge.path("total").asLong()).isEqualTo(1);
        assertThat(huge.path("items").size()).isZero();

        for (var query : List.of("page=-1&size=20", "page=1000001&size=20", "page=2147483647&size=20",
                "page=0&size=0", "page=0&size=101", "page=0&size=-1")) {
            var response = send("a:employee", "GET", "/api/notifications?" + query, null);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(nodes(response).path("code").asText()).as(query).isEqualTo("invalid_page");
        }
        var malformed = send("a:employee", "GET", "/api/notifications?page=abc&size=20", null);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(nodes(malformed).path("code").asText()).isEqualTo("invalid_request");
    }

    @Test void listItemsExposeExactlyTheAgreedFieldsAndNoPurchaseContent() throws Exception {
        var seeded = seed("a", "request.approved", NOW, Map.of("a:employee", EARLIER));
        var item = nodes(list("a:employee")).path("items").get(0);
        assertThat(item.size()).isEqualTo(8);
        assertThat(item.path("id").asText()).isEqualTo(seeded.notifications().get("a:employee").toString());
        assertThat(item.path("eventId").asText()).isEqualTo(seeded.eventId().toString());
        assertThat(item.path("eventType").asText()).isEqualTo("request.approved");
        assertThat(item.path("requestId").asText()).isEqualTo(seeded.requestId().toString());
        assertThat(item.path("correlationId").asText()).isEqualTo(seeded.correlationId().toString());
        assertThat(Instant.parse(item.path("occurredAt").asText())).isEqualTo(NOW);
        assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(EARLIER);
        assertThat(item.get("readAt")).isNotNull();
        assertThat(item.path("readAt").isNull()).isTrue();
        for (var forbidden : List.of("title", "justification", "amount", "status", "envelope", "transition",
                "recipient", "email", "errorCode", "reason")) {
            assertThat(item.get(forbidden)).as(forbidden).isNull();
        }
        var body = list("a:employee").body();
        assertThat(body).doesNotContain("Private laptop title", "Confidential justification text",
                "RAW-ENVELOPE-MARKER", "@demo.invalid");
    }

    @Test void backlogRequiresTheCurrentCompanyAdministrator() throws Exception {
        seed("a", "request.submitted", NOW, Map.of("a:employee", EARLIER));
        for (var role : List.of("employee", "manager", "finance")) {
            var response = send("a:" + role, "GET", "/api/admin/notifications/backlog", null);
            assertThat(response.statusCode()).as(role).isEqualTo(403);
        }
        var admin = send("a:admin", "GET", "/api/admin/notifications/backlog", null);
        assertThat(admin.statusCode()).as(admin.body()).isEqualTo(200);
        assertThat(nodes(admin).path("companyId").asText()).isEqualTo(id("a").toString());

        owner.update("DELETE FROM membership_role WHERE company_id = ? AND membership_id = ? AND role = 'COMPANY_ADMIN'",
                id("a"), id("a:admin:membership"));
        assertThat(send("a:admin", "GET", "/api/admin/notifications/backlog", null).statusCode()).isEqualTo(403);
    }

    @Test void backlogReportsEveryFixedFieldAtZero() throws Exception {
        var body = nodes(send("a:admin", "GET", "/api/admin/notifications/backlog", null));
        assertThat(body.path("companyId").asText()).isEqualTo(id("a").toString());
        assertThat(body.path("dispatch").size()).isEqualTo(4);
        assertThat(body.path("email").size()).isEqualTo(5);
        assertThat(body.path("deliveries").size()).isEqualTo(2);
        for (var field : List.of("pending", "leased", "published", "quarantined"))
            assertThat(body.path("dispatch").path(field).asLong()).as(field).isZero();
        for (var field : List.of("pending", "leased", "sent", "quarantined", "skipped"))
            assertThat(body.path("email").path(field).asLong()).as(field).isZero();
        for (var field : List.of("materialized", "skipped"))
            assertThat(body.path("deliveries").path(field).asLong()).as(field).isZero();
        assertThat(body.path("inboxTotal").asLong()).isZero();
    }

    @Test void backlogCountsMixedStatesOnlyForTheCurrentCompany() throws Exception {
        var pendingEvent = seed("a", "request.submitted", NOW, recipients("a:employee", "a:manager", "a:finance", "a:admin")).eventId();
        var leasedEvent = seed("a", "request.submitted", NOW, recipients("a:employee")).eventId();
        var publishedEvent = seed("a", "request.submitted", NOW, recipients("a:employee")).eventId();
        var quarantinedEvent = seed("a", "request.submitted", NOW, recipients("a:employee", "a:finance", "a:manager")).eventId();
        worker.update("UPDATE outbox_dispatch SET state = 'LEASED', lease_token = ?, lease_until = now() + interval '1 minute' WHERE event_id = ?",
                UUID.randomUUID(), leasedEvent);
        worker.update("UPDATE outbox_dispatch SET state = 'PUBLISHED', published_at = now() WHERE event_id = ?", publishedEvent);
        worker.update("UPDATE outbox_dispatch SET state = 'QUARANTINED', error_code = 'publish_failed' WHERE event_id = ?", quarantinedEvent);

        emailJob("a", materializedDelivery("a", pendingEvent, "a:employee", "EMAIL"), pendingEvent, "a:employee", "PENDING");
        emailJob("a", materializedDelivery("a", pendingEvent, "a:manager", "EMAIL"), pendingEvent, "a:manager", "LEASED");
        emailJob("a", materializedDelivery("a", pendingEvent, "a:finance", "EMAIL"), pendingEvent, "a:finance", "SENT");
        emailJob("a", materializedDelivery("a", pendingEvent, "a:admin", "EMAIL"), pendingEvent, "a:admin", "QUARANTINED");
        emailJob("a", materializedDelivery("a", leasedEvent, "a:employee", "EMAIL"), leasedEvent, "a:employee", "SKIPPED");

        inAppNotification("a", materializedDelivery("a", publishedEvent, "a:employee", "IN_APP"), publishedEvent, "a:employee", NOW);
        inAppNotification("a", materializedDelivery("a", quarantinedEvent, "a:finance", "IN_APP"), quarantinedEvent, "a:finance", NOW);
        skippedDelivery("a", quarantinedEvent, "a:manager", "IN_APP");

        inbox(id("a"), pendingEvent);
        inbox(id("a"), leasedEvent);

        var other = seed("b", "request.submitted", NOW, Map.of("b:employee", EARLIER));
        inbox(id("b"), other.eventId());

        var a = nodes(send("a:admin", "GET", "/api/admin/notifications/backlog", null));
        assertThat(a.path("companyId").asText()).isEqualTo(id("a").toString());
        assertThat(a.path("dispatch").path("pending").asLong()).isEqualTo(1);
        assertThat(a.path("dispatch").path("leased").asLong()).isEqualTo(1);
        assertThat(a.path("dispatch").path("published").asLong()).isEqualTo(1);
        assertThat(a.path("dispatch").path("quarantined").asLong()).isEqualTo(1);
        assertThat(a.path("email").path("pending").asLong()).isEqualTo(1);
        assertThat(a.path("email").path("leased").asLong()).isEqualTo(1);
        assertThat(a.path("email").path("sent").asLong()).isEqualTo(1);
        assertThat(a.path("email").path("quarantined").asLong()).isEqualTo(1);
        assertThat(a.path("email").path("skipped").asLong()).isEqualTo(1);
        assertThat(a.path("deliveries").path("materialized").asLong()).isEqualTo(7);
        assertThat(a.path("deliveries").path("skipped").asLong()).isEqualTo(1);
        assertThat(a.path("inboxTotal").asLong()).isEqualTo(2);
        assertThat(a.toString()).doesNotContain(id("b").toString());

        var b = nodes(send("b:admin", "GET", "/api/admin/notifications/backlog", null));
        assertThat(b.path("companyId").asText()).isEqualTo(id("b").toString());
        assertThat(b.path("dispatch").path("pending").asLong()).isEqualTo(1);
        assertThat(b.path("dispatch").path("leased").asLong()).isZero();
        assertThat(b.path("dispatch").path("published").asLong()).isZero();
        assertThat(b.path("dispatch").path("quarantined").asLong()).isZero();
        assertThat(b.path("email").path("pending").asLong()).isZero();
        assertThat(b.path("email").path("leased").asLong()).isZero();
        assertThat(b.path("email").path("sent").asLong()).isZero();
        assertThat(b.path("email").path("quarantined").asLong()).isZero();
        assertThat(b.path("email").path("skipped").asLong()).isZero();
        assertThat(b.path("deliveries").path("materialized").asLong()).isEqualTo(1);
        assertThat(b.path("deliveries").path("skipped").asLong()).isZero();
        assertThat(b.path("inboxTotal").asLong()).isEqualTo(1);
    }

    @Test void generatedOpenApiDescribesTheNotificationContracts() throws Exception {
        var response = client.send(HttpRequest.newBuilder(url("/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var api = nodes(response);

        var list = api.path("paths").path("/api/notifications").path("get");
        assertThat(list.path("operationId").asText()).isEqualTo("listNotifications");
        assertThat(list.path("security").toString()).contains("bearerAuth");
        var pageSchema = schema(api, list.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText());
        for (var field : List.of("page", "size", "total", "items"))
            assertThat(pageSchema.path("properties").get(field)).as(field).isNotNull();
        var itemSchema = schema(api, pageSchema.path("properties").path("items").path("items").path("$ref").asText());
        for (var field : List.of("id", "eventId", "eventType", "requestId", "correlationId", "occurredAt", "createdAt", "readAt"))
            assertThat(itemSchema.path("properties").get(field)).as(field).isNotNull();
        assertThat(itemSchema.path("properties").get("title")).isNull();
        assertThat(itemSchema.path("properties").get("justification")).isNull();
        assertThat(itemSchema.path("properties").size()).as("notification metadata field count").isEqualTo(8);
        assertThat(itemSchema.path("properties").get("amount")).isNull();

        // Corrective API-L2 evidence: the request list documents its own application/json 200 page whose
        // schema and nested summary ref resolve in the same generated document as the notification page.
        var requestList = api.path("paths").path("/api/requests").path("get");
        assertThat(requestList.path("operationId").asText()).isEqualTo("listRequests");
        assertThat(requestList.path("responses").path("200").isMissingNode())
                .as("GET /api/requests must document its application/json 200 response").isFalse();
        var requestPageRef = requestList.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText();
        var notificationPageRef = list.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText();
        assertThat(requestPageRef).as("request page schema ref").startsWith("#/components/schemas/");
        assertThat(notificationPageRef).as("notification page schema ref").startsWith("#/components/schemas/");
        assertThat(requestPageRef).as("request and notification pages must be distinct schemas")
                .isNotEqualTo(notificationPageRef);
        var requestPage = schema(api, requestPageRef);
        for (var field : List.of("page", "size", "total", "items"))
            assertThat(requestPage.path("properties").get(field)).as(field).isNotNull();
        var requestItem = schema(api, requestPage.path("properties").path("items").path("items").path("$ref").asText());
        assertThat(requestItem.path("properties").size()).as("request summary field count").isEqualTo(7);
        for (var field : List.of("id", "departmentId", "title", "amount", "status", "version", "createdAt"))
            assertThat(requestItem.path("properties").get(field)).as(field).isNotNull();
        for (var forbidden : List.of("eventId", "eventType", "requestId", "correlationId", "occurredAt", "readAt"))
            assertThat(requestItem.path("properties").get(forbidden)).as(forbidden).isNull();

        var read = api.path("paths").path("/api/notifications/{id}/read").path("post");
        assertThat(read.path("operationId").asText()).isEqualTo("markNotificationRead");
        assertThat(read.path("responses").path("404").isMissingNode()).isFalse();
        var readSchema = schema(api, read.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText());
        assertThat(readSchema.path("properties").get("id")).isNotNull();
        assertThat(readSchema.path("properties").get("readAt")).isNotNull();

        var backlog = api.path("paths").path("/api/admin/notifications/backlog").path("get");
        assertThat(backlog.path("operationId").asText()).isEqualTo("getNotificationBacklog");
        assertThat(backlog.path("responses").path("403").isMissingNode()).isFalse();
        assertThat(backlog.path("security").toString()).contains("bearerAuth");
        var backlogSchema = schema(api, backlog.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText());
        for (var field : List.of("companyId", "dispatch", "email", "deliveries", "inboxTotal"))
            assertThat(backlogSchema.path("properties").get(field)).as(field).isNotNull();
    }

    private record Seeded(UUID requestId, UUID eventId, UUID correlationId, Map<String, UUID> notifications) {}

    /**
     * Owner-only canonical fixture: one request/outbox event with recipient snapshots. When a
     * recipient has a creation time, the worker login materializes the IN_APP delivery and
     * in-app notification row, which is setup, not consumption evidence.
     */
    private Seeded seed(String company, String eventType, Instant occurredAt, Map<String, Instant> recipients) {
        var companyId = id(company);
        var requestId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        owner.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                    title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'Private laptop title', 'Confidential justification text', 10.00, now(), now(), ?)
                """, requestId, companyId, id(company + ":employee:identity"), id(company + ":employee:membership"),
                id(company + ":engineering"), correlationId);
        owner.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, occurred_at, envelope)
                VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, eventId, companyId, requestId, eventType, correlationId, Timestamp.from(occurredAt),
                envelope(companyId, requestId, eventId, eventType, correlationId));
        var notifications = new HashMap<String, UUID>();
        recipients.forEach((actor, createdAt) -> {
            owner.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                    companyId, eventId, id(actor + ":identity"), id(actor + ":membership"));
            if (createdAt == null) return;
            notifications.put(actor, inAppNotification(company, materializedDelivery(company, eventId, actor, "IN_APP"),
                    eventId, actor, createdAt));
        });
        return new Seeded(requestId, eventId, correlationId, notifications);
    }

    private static Map<String, Instant> recipients(String... actors) {
        var map = new LinkedHashMap<String, Instant>();
        for (var actor : actors) map.put(actor, null);
        return map;
    }

    private UUID materializedDelivery(String company, UUID eventId, String actor, String channel) {
        var delivery = UUID.randomUUID();
        worker.update("INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, channel, outcome) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'MATERIALIZED')",
                delivery, id(company), eventId, id(actor + ":identity"), id(actor + ":membership"), channel);
        return delivery;
    }

    private void skippedDelivery(String company, UUID eventId, String actor, String channel) {
        worker.update("INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, channel, outcome, reason_code) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'SKIPPED', 'recipient_inactive')",
                UUID.randomUUID(), id(company), eventId, id(actor + ":identity"), id(actor + ":membership"), channel);
    }

    private UUID inAppNotification(String company, UUID delivery, UUID eventId, String actor, Instant createdAt) {
        var notification = UUID.randomUUID();
        worker.update("INSERT INTO in_app_notification (id, delivery_id, company_id, event_id, identity_id, membership_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                notification, delivery, id(company), eventId, id(actor + ":identity"), id(actor + ":membership"),
                Timestamp.from(createdAt));
        return notification;
    }

    private void emailJob(String company, UUID delivery, UUID eventId, String actor, String state) {
        var base = "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id";
        var values = new StringBuilder("VALUES (?, ?, ?, ?, ?, ?");
        var parameters = new ArrayList<Object>();
        Collections.addAll(parameters, UUID.randomUUID(), delivery, id(company), eventId, id(actor + ":identity"), id(actor + ":membership"));
        switch (state) {
            case "PENDING" -> { }
            case "LEASED" -> {
                base += ", state, lease_token, lease_until"; values.append(", 'LEASED', ?, now() + interval '1 minute'");
                parameters.add(UUID.randomUUID());
            }
            case "SENT" -> {
                base += ", state, sent_at"; values.append(", 'SENT', now()");
            }
            case "QUARANTINED" -> {
                base += ", state, error_code"; values.append(", 'QUARANTINED', 'smtp_failed'");
            }
            case "SKIPPED" -> {
                base += ", state, error_code"; values.append(", 'SKIPPED', 'recipient_inactive'");
            }
            default -> throw new IllegalArgumentException("Unsupported email fixture state " + state);
        }
        worker.update(base + ") " + values + ")", parameters.toArray());
    }

    private void inbox(UUID company, UUID eventId) {
        worker.update("INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)", company, eventId);
    }

    private static String envelope(UUID company, UUID request, UUID event, String type, UUID correlation) {
        return """
                {"eventId":"%s","companyId":"%s","requestId":"%s","type":"%s","correlationId":"%s","schemaVersion":1,
                 "rawEnvelopeMarker":"RAW-ENVELOPE-MARKER","transition":{"fromStatus":"DRAFT","toStatus":"DRAFT"}}
                """.formatted(event, company, request, type, correlation);
    }

    /** Waits (bounded, below the app lock_timeout) until the expected HTTP reads are blocked on the held delivery row. */
    private void awaitBlockedReadUpdates(int expected) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            var blocked = owner.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE usename = 'procureflow_app' AND state = 'active' AND wait_event_type = 'Lock'
                      AND query LIKE 'UPDATE in_app_notification%'
                    """, Integer.class);
            if (blocked != null && blocked >= expected) return;
            Thread.sleep(25);
        }
        fail("expected %d concurrent read updates to wait on the delivery row lock", expected);
    }

    private Instant persistedReadAt(UUID notification) {
        var value = owner.queryForObject("SELECT read_at FROM in_app_notification WHERE id = ?", Timestamp.class, notification);
        return value == null ? null : value.toInstant();
    }

    private static JsonNode schema(JsonNode api, String reference) {
        assertThat(reference).startsWith("#/components/schemas/");
        return api.path("components").path("schemas").path(reference.substring(reference.lastIndexOf('/') + 1));
    }

    private static List<UUID> ids(JsonNode items) {
        var result = new ArrayList<UUID>();
        for (var item : items) result.add(UUID.fromString(item.path("id").asText()));
        return result;
    }

    private String token(String actor) { return TOKENS.token(id(actor + ":keycloak").toString()); }
    private URI url(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> list(String actor) throws Exception { return send(actor, "GET", "/api/notifications?page=0&size=20", null); }
    private HttpResponse<String> read(String actor, UUID id) throws Exception { return send(actor, "POST", "/api/notifications/" + id + "/read", null); }

    private HttpResponse<String> send(String actor, String method, String path, Object body) throws Exception {
        return sendToken(token(actor), method, path, body);
    }

    private HttpResponse<String> sendToken(String token, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode nodes(HttpResponse<String> response) { return JSON.readTree(response.body()); }
}

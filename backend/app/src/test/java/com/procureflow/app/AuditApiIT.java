package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-HTTP acceptance for the bounded company-admin audit metadata read API against real
 * PostgreSQL and signed JWT fixtures. Audit rows are inserted by owner-only setup SQL; the API
 * itself is always exercised through a real HTTP request and the runtime application login.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditApiIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-10-19T09:00:00.123456Z");
    static final Instant EARLIER = NOW.minusSeconds(3600);
    static final Instant EARLIEST = NOW.minusSeconds(7200);
    static final String PATH = "/api/admin/audit";

    @LocalServerPort int port;
    @Autowired DemoData demo;
    @Autowired JdbcTemplate applicationJdbc;
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

    @AfterEach void closeClient() { if (client != null) client.close(); }
    @AfterAll static void closeTokens() { TOKENS.close(); }

    @Test void requiresAValidlySignedUnexpiredBearerToken() throws Exception {
        seedAudit("a", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "a:admin");
        assertThat(sendToken(null, PATH, Map.of()).statusCode()).isEqualTo(401);

        var forged = TOKENS.token(id("a:admin:keycloak").toString(), ignored -> {}, true);
        assertThat(sendToken(forged, PATH, Map.of()).statusCode()).isEqualTo(401);

        var expired = TOKENS.token(id("a:admin:keycloak").toString(),
                claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(60))), false);
        assertThat(sendToken(expired, PATH, Map.of()).statusCode()).isEqualTo(401);

        assertThat(send("a:admin", PATH).statusCode()).isEqualTo(200);
    }

    @Test void rejectsNonAdminsEvenWithUntrustedTokenRolesAndSpoofedCallerScope() throws Exception {
        seedAudit("a", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "a:admin");
        seedAudit("b", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "b:admin");

        for (var role : List.of("employee", "manager", "finance")) {
            var spoofed = TOKENS.token(id("a:" + role + ":keycloak").toString(), claims -> {
                claims.claim("companyId", id("b").toString());
                claims.claim("company_id", id("b").toString());
                claims.claim("roles", List.of("COMPANY_ADMIN"));
                claims.claim("realm_access", Map.of("roles", List.of("COMPANY_ADMIN")));
            }, false);
            var response = sendToken(spoofed,
                    PATH + "?page=0&size=20&companyId=" + id("b") + "&company_id=" + id("b"),
                    Map.of("X-Company-Id", id("b").toString(),
                            "X-Identity-Id", id("b:" + role + ":identity").toString()));
            assertThat(response.statusCode()).as(role).isEqualTo(403);
            assertThat(nodes(response).path("code").asText()).as(role).isEqualTo("forbidden");
        }
    }

    @Test void adminQueryAndHeadersCannotOverrideTheMembershipCompanyAndForeignIdsAreNotSelectable() throws Exception {
        var ownEntity = UUID.randomUUID();
        var foreignEntity = UUID.randomUUID();
        var ownAudit = seedAudit("a", "request.created", ownEntity, NOW, UUID.randomUUID(), "a:admin");
        seedAudit("b", "request.created", foreignEntity, NOW, UUID.randomUUID(), "b:admin");

        var response = send("a:admin", PATH + "?page=0&size=100&companyId=" + id("b") + "&company_id=" + id("b")
                + "&action=request.created&entityId=" + foreignEntity,
                Map.of("X-Company-Id", id("b").toString()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body = nodes(response);
        assertThat(body.path("total").asLong()).isEqualTo(1);
        assertThat(ids(body.path("items"))).containsExactly(ownAudit);
        assertThat(body.path("items").get(0).path("entityId").asText()).isEqualTo(ownEntity.toString());
        assertThat(response.body()).doesNotContain(foreignEntity.toString(), id("b:admin:identity").toString(),
                id("b:admin:membership").toString());
        assertThat(send("a:admin", PATH + "/" + foreignEntity).statusCode()).isEqualTo(404);
    }

    @Test void eachAdminSeesOnlyOwnCompanyEntriesEvenWhenTimestampsTie() throws Exception {
        var aEntries = new ArrayList<UUID>();
        var aEntities = new ArrayList<UUID>();
        for (int i = 0; i < 8; i++) {
            var entity = UUID.randomUUID();
            aEntities.add(entity);
            aEntries.add(seedAudit("a", "request.created", entity, NOW, UUID.randomUUID(), "a:admin"));
        }
        var bEntries = new ArrayList<UUID>();
        var bEntities = new ArrayList<UUID>();
        for (int i = 0; i < 9; i++) {
            var entity = UUID.randomUUID();
            bEntities.add(entity);
            bEntries.add(seedAudit("b", "request.created", entity, NOW, UUID.randomUUID(), "b:admin"));
        }

        var aResponse = send("a:admin", PATH + "?page=0&size=100");
        var a = nodes(aResponse);
        assertThat(a.path("total").asLong()).isEqualTo(8);
        assertThat(ids(a.path("items"))).containsExactlyElementsOf(descendingUuid(aEntries));
        for (var entity : bEntities)
            assertThat(aResponse.body()).doesNotContain(entity.toString());
        assertThat(aResponse.body()).doesNotContain(id("b:admin:identity").toString(),
                id("b:admin:membership").toString());

        var bResponse = send("b:admin", PATH + "?page=0&size=100");
        var b = nodes(bResponse);
        assertThat(b.path("total").asLong()).isEqualTo(9);
        assertThat(ids(b.path("items"))).containsExactlyElementsOf(descendingUuid(bEntries));
        for (var entity : aEntities)
            assertThat(bResponse.body()).doesNotContain(entity.toString());
        assertThat(bResponse.body()).doesNotContain(id("a:admin:identity").toString(),
                id("a:admin:membership").toString());
    }

    @Test void revokedAdminRoleIsForbiddenWithTheSameUnexpiredToken() throws Exception {
        seedAudit("a", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "a:admin");
        var token = token("a:admin");
        assertThat(sendToken(token, PATH, Map.of()).statusCode()).isEqualTo(200);

        owner.update("DELETE FROM membership_role WHERE company_id = ? AND membership_id = ? AND role = 'COMPANY_ADMIN'",
                id("a"), id("a:admin:membership"));
        var denied = sendToken(token, PATH, Map.of());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(nodes(denied).path("code").asText()).isEqualTo("forbidden");
    }

    @Test void inactiveMembershipIsForbiddenWithTheSameUnexpiredToken() throws Exception {
        seedAudit("a", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "a:admin");
        var token = token("a:admin");
        assertThat(sendToken(token, PATH, Map.of()).statusCode()).isEqualTo(200);

        owner.update("UPDATE membership SET active = false WHERE id = ?", id("a:admin:membership"));
        var denied = sendToken(token, PATH, Map.of());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(nodes(denied).path("code").asText()).isEqualTo("forbidden");
    }

    @Test void paginationCoversFirstNextTiesEmptyOutOfRangeAndLongOffsets() throws Exception {
        var latest = seedAudit("a", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "a:admin");
        var tied = List.of(
                seedAudit("a", "request.created", UUID.randomUUID(), EARLIER, UUID.randomUUID(), "a:admin"),
                seedAudit("a", "request.created", UUID.randomUUID(), EARLIER, UUID.randomUUID(), "a:admin"),
                seedAudit("a", "request.created", UUID.randomUUID(), EARLIER, UUID.randomUUID(), "a:admin"));
        var oldest = seedAudit("a", "request.created", UUID.randomUUID(), EARLIEST, UUID.randomUUID(), "a:admin");
        var expected = new ArrayList<UUID>();
        expected.add(latest);
        expected.addAll(descendingUuid(tied));
        expected.add(oldest);

        var page0 = nodes(send("a:admin", PATH + "?page=0&size=2"));
        assertThat(page0.path("page").asInt()).isZero();
        assertThat(page0.path("size").asInt()).isEqualTo(2);
        assertThat(page0.path("total").asLong()).isEqualTo(5);
        assertThat(ids(page0.path("items"))).containsExactly(expected.get(0), expected.get(1));

        var page1 = nodes(send("a:admin", PATH + "?page=1&size=2"));
        assertThat(page1.path("total").asLong()).isEqualTo(5);
        assertThat(ids(page1.path("items"))).containsExactly(expected.get(2), expected.get(3));

        var page2 = nodes(send("a:admin", PATH + "?page=2&size=2"));
        assertThat(page2.path("total").asLong()).isEqualTo(5);
        assertThat(ids(page2.path("items"))).containsExactly(expected.get(4));

        var page3 = nodes(send("a:admin", PATH + "?page=3&size=2"));
        assertThat(page3.path("total").asLong()).isEqualTo(5);
        assertThat(page3.path("items").isArray()).isTrue();
        assertThat(page3.path("items").size()).isZero();

        // A valid int page whose long offset exceeds int range must not overflow or 400.
        var huge = nodes(send("a:admin", PATH + "?page=2147483647&size=100"));
        assertThat(huge.path("total").asLong()).isEqualTo(5);
        assertThat(huge.path("items").size()).isZero();

        var union = new ArrayList<UUID>();
        union.addAll(ids(page0.path("items")));
        union.addAll(ids(page1.path("items")));
        union.addAll(ids(page2.path("items")));
        assertThat(union).containsExactlyElementsOf(expected);
    }

    @Test void emptyScopeAndOutOfRangePageReturnItemsArrayWithCorrectTotal() throws Exception {
        var empty = nodes(send("a:admin", PATH + "?page=0&size=20"));
        assertThat(empty.path("total").asLong()).isZero();
        assertThat(empty.path("items").isArray()).isTrue();
        assertThat(empty.path("items").size()).isZero();

        seedAudit("a", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "a:admin");
        seedAudit("b", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "b:admin");
        var outOfRange = nodes(send("a:admin", PATH + "?page=50&size=20"));
        assertThat(outOfRange.path("total").asLong()).isEqualTo(1);
        assertThat(outOfRange.path("items").isArray()).isTrue();
        assertThat(outOfRange.path("items").size()).isZero();
        var b = nodes(send("b:admin", PATH + "?page=0&size=20"));
        assertThat(b.path("total").asLong()).isEqualTo(1);
    }

    @Test void invalidPageOrSizeReturnsFixedInvalidRequestBeforeDatabasePagination() throws Exception {
        for (var query : List.of("page=-1&size=20", "page=0&size=0", "page=0&size=101",
                "page=0&size=-1", "page=abc&size=20", "page=0&size=abc",
                "page=2147483648&size=20", "page=0&size=2147483648", "page=0&size=1000")) {
            var response = send("a:admin", PATH + "?" + query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(nodes(response).path("code").asText()).as(query).isEqualTo("invalid_request");
        }
        assertThat(send("a:admin", PATH + "?page=0&size=100").statusCode()).isEqualTo(200);
        assertThat(send("a:admin", PATH + "?page=0&size=1").statusCode()).isEqualTo(200);
    }

    @Test void entriesExposeOnlyBoundedMetadataAndPreservePreciseUtc() throws Exception {
        var entityId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        seedRequest("a", entityId, "Private laptop title", "Confidential justification text", NOW, correlationId);
        var auditId = seedAudit("a", "request.approved", entityId, NOW, correlationId, "a:admin");

        var response = send("a:admin", PATH + "?page=0&size=20");
        var item = nodes(response).path("items").get(0);
        assertThat(item.size()).isEqualTo(7);
        assertThat(item.path("id").asText()).isEqualTo(auditId.toString());
        assertThat(item.path("action").asText()).isEqualTo("request.approved");
        assertThat(item.path("entityId").asText()).isEqualTo(entityId.toString());
        assertThat(item.path("actorIdentityId").asText()).isEqualTo(id("a:admin:identity").toString());
        assertThat(item.path("actorMembershipId").asText()).isEqualTo(id("a:admin:membership").toString());
        assertThat(item.path("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(Instant.parse(item.path("occurredAt").asText())).isEqualTo(NOW);
        for (var forbidden : List.of("companyId", "payload", "title", "justification", "reason", "requestTitle",
                "subject", "issuer", "email", "token", "amount", "status"))
            assertThat(item.get(forbidden)).as(forbidden).isNull();
        assertThat(response.body()).doesNotContain("Private laptop title", "Confidential justification text", "@demo.invalid");
    }

    @Test void getDoesNotInsertOrMutateAuditAndBusinessRows() throws Exception {
        var entityId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();
        seedRequest("a", entityId, "Read-only title", "Read-only justification", NOW, correlationId);
        seedAudit("a", "request.approved", entityId, NOW, correlationId, "a:admin");
        seedAudit("b", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "b:admin");

        var auditBefore = owner.queryForList("""
                SELECT id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at
                FROM audit_entry ORDER BY id
                """);
        var requestBefore = owner.queryForList("""
                SELECT id, title, justification, amount, status, version, updated_at FROM purchase_request ORDER BY id
                """);
        var companyBefore = owner.queryForList("SELECT id, slug, name FROM company ORDER BY id");

        assertThat(send("a:admin", PATH + "?page=0&size=20").statusCode()).isEqualTo(200);
        assertThat(send("a:admin", PATH + "?page=0&size=20").statusCode()).isEqualTo(200);

        assertThat(owner.queryForList("""
                SELECT id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at
                FROM audit_entry ORDER BY id
                """)).isEqualTo(auditBefore);
        assertThat(owner.queryForList("""
                SELECT id, title, justification, amount, status, version, updated_at FROM purchase_request ORDER BY id
                """)).isEqualTo(requestBefore);
        assertThat(owner.queryForList("SELECT id, slug, name FROM company ORDER BY id")).isEqualTo(companyBefore);
    }

    @Test void auditEntryColumnsAreNotNullableAndRuntimeLoginRemainsAppendOnly() throws Exception {
        var columns = owner.queryForList("""
                SELECT column_name, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'audit_entry'
                """);
        var nullability = new HashMap<String, String>();
        for (var column : columns) nullability.put((String) column.get("column_name"), (String) column.get("is_nullable"));
        for (var column : List.of("id", "company_id", "actor_identity_id", "actor_membership_id",
                "action", "entity_id", "correlation_id", "occurred_at"))
            assertThat(nullability).as(column).containsEntry(column, "NO");

        seedAudit("a", "request.created", UUID.randomUUID(), NOW, UUID.randomUUID(), "a:admin");
        assertThat(applicationJdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("procureflow_app");
        assertThat(applicationJdbc.queryForObject("SELECT count(*) FROM audit_entry", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> applicationJdbc.update("UPDATE audit_entry SET action = 'forged'"))
                .rootCause().isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> applicationJdbc.update("DELETE FROM audit_entry"))
                .rootCause().isInstanceOf(SQLException.class);
        assertThat(owner.queryForObject("SELECT action FROM audit_entry", String.class)).isEqualTo("request.created");
    }

    @Test void generatedOpenApiDescribesTheAuditContract() throws Exception {
        var response = client.send(HttpRequest.newBuilder(url("/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var api = nodes(response);

        var audit = api.path("paths").path(PATH).path("get");
        assertThat(audit.path("operationId").asText()).isEqualTo("getCompanyAudit");
        assertThat(audit.path("security").toString()).contains("bearerAuth");
        assertThat(audit.path("responses").path("400").isMissingNode()).isFalse();
        assertThat(audit.path("responses").path("401").isMissingNode()).isFalse();
        assertThat(audit.path("responses").path("403").isMissingNode()).isFalse();

        var pageSchema = schema(api, audit.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText());
        for (var field : List.of("page", "size", "total", "items"))
            assertThat(pageSchema.path("properties").get(field)).as(field).isNotNull();
        var entrySchema = schema(api, pageSchema.path("properties").path("items").path("items").path("$ref").asText());
        for (var field : List.of("id", "action", "entityId", "actorIdentityId", "actorMembershipId",
                "correlationId", "occurredAt"))
            assertThat(entrySchema.path("properties").get(field)).as(field).isNotNull();
        assertThat(entrySchema.path("properties").size()).as("audit entry metadata field count").isEqualTo(7);
        for (var forbidden : List.of("companyId", "payload", "title", "justification", "reason",
                "requestTitle", "subject", "issuer", "email", "token"))
            assertThat(entrySchema.path("properties").get(forbidden)).as(forbidden).isNull();
    }

    private UUID seedAudit(String company, String action, UUID entityId, Instant occurredAt,
                           UUID correlationId, String actor) {
        var auditId = UUID.randomUUID();
        owner.update("""
                INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id,
                    correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, auditId, id(company), id(actor + ":identity"), id(actor + ":membership"), action, entityId,
                correlationId, Timestamp.from(occurredAt));
        return auditId;
    }

    private UUID seedRequest(String company, UUID requestId, String title, String justification,
                             Instant occurredAt, UUID correlationId) {
        owner.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id,
                    department_id, title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 10.00, ?, ?, ?)
                """, requestId, id(company), id(company + ":employee:identity"), id(company + ":employee:membership"),
                id(company + ":engineering"), title, justification, Timestamp.from(occurredAt),
                Timestamp.from(occurredAt), correlationId);
        return requestId;
    }

    private static List<UUID> descendingUuid(List<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
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
    private HttpResponse<String> send(String actor, String path) throws Exception { return send(actor, path, Map.of()); }

    private HttpResponse<String> send(String actor, String path, Map<String, String> headers) throws Exception {
        return sendToken(token(actor), path, headers);
    }

    private HttpResponse<String> sendToken(String token, String path, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        request.GET();
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode nodes(HttpResponse<String> response) { return JSON.readTree(response.body()); }
}

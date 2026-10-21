package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

/**
 * Real-HTTP acceptance for the application-side JSON body bound on the directly published
 * application port (the nginx edge is checked separately by the disposable Compose verifier).
 * Bodies at the documented form size stay accepted; oversized declared-length and chunked
 * bodies are refused with 413 before Spring MVC deserializes them. Authentication still runs
 * first, so an unauthenticated oversized request remains 401.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestSizeBoundIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int DEFAULT_LIMIT = 16_384;

    @LocalServerPort int port;
    @Autowired DemoData demo;
    @Autowired JdbcTemplate applicationJdbc;
    HttpClient client;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
        properties.add("app.security.issuer", TOKENS::issuer);
        properties.add("app.security.jwk-set-uri", TOKENS::jwks);
    }

    @BeforeEach void reset() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
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

    @Test void documentedFormPayloadStaysAccepted() throws Exception {
        var response = send(token(), UUID.randomUUID().toString(), validBody("normal").getBytes(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    @Test void invalidSmallFormStaysAValidationError() throws Exception {
        var response = send(token(), UUID.randomUUID().toString(),
                "{\"title\":\"\",\"justification\":\"\",\"amount\":0}".getBytes(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("validation_failed");
    }

    @Test void oversizedDeclaredContentLengthIsRejectedWith413() throws Exception {
        var oversized = oversizedBody(DEFAULT_LIMIT + 4096);
        var response = send(token(), UUID.randomUUID().toString(), oversized);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("payload_too_large");
        assertThat(response.body()).doesNotContain("invalid_request", "validation_failed");
    }

    @Test void oversizedChunkedBodyIsRejectedWith413() throws Exception {
        var oversized = oversizedBody(DEFAULT_LIMIT + 8192);
        var request = base("/api/requests")
                .header("Authorization", "Bearer " + token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                // An input stream has no known length, so the client must use chunked transfer.
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("payload_too_large");
    }

    @Test void exactLimitIsAcceptedAndOneByteMoreIsRejected() throws Exception {
        var accepted = padTo(validBody("boundary"), DEFAULT_LIMIT);
        var acceptedResponse = send(token(), UUID.randomUUID().toString(), accepted);
        assertThat(acceptedResponse.statusCode()).as(acceptedResponse.body()).isEqualTo(201);

        var rejected = padTo(validBody("boundary"), DEFAULT_LIMIT + 1);
        var rejectedResponse = send(token(), UUID.randomUUID().toString(), rejected);
        assertThat(rejectedResponse.statusCode()).isEqualTo(413);
    }

    @Test void unauthenticatedOversizedRequestStaysUnauthorized() throws Exception {
        var request = base("/api/requests")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(oversizedBody(DEFAULT_LIMIT + 4096)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private String token() {
        return TOKENS.token(id("a:employee:keycloak").toString());
    }

    private HttpResponse<String> send(String token, String key, byte[] body) throws Exception {
        var request = base("/api/requests")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
    }

    private String validBody(String label) {
        return "{\"title\":\"P8 " + label + " body\",\"justification\":\"Fictional local-only request body bound fixture\","
                + "\"amount\":10.50}";
    }

    private static byte[] oversizedBody(int bytes) {
        var prefix = "{\"title\":\"P8 oversized body\",\"justification\":\"";
        var suffix = "\",\"amount\":10.50}";
        int padding = Math.max(0, bytes - prefix.length() - suffix.length());
        return (prefix + "x".repeat(padding) + suffix).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] padTo(String json, int bytes) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length > bytes) throw new IllegalArgumentException("base body already exceeds the target size");
        var padded = new byte[bytes];
        System.arraycopy(body, 0, padded, 0, body.length);
        java.util.Arrays.fill(padded, body.length, bytes, (byte) ' ');
        return padded;
    }
}

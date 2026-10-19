package com.procureflow.worker.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL 18.6 and pinned Mailpit fixtures for email-runner behavior. Canonical
 * event/recipient/request rows are owner-only setup; the email delivery and job rows that the
 * runner under test owns are seeded through the actual {@code procureflow_worker} login. This is
 * job-lifecycle setup, not proof of consumer materialization.
 */
public final class EmailServiceFixture {
    public static final String WORKER_USER = "procureflow_worker";
    public static final String DEFAULT_EVENT_TYPE = "request.submitted";
    /** Distinctive canonical purchase content that must never appear in a captured message. */
    public static final String TITLE_MARKER = "ConfidentialPurchaseTitleMarker";
    public static final String JUSTIFICATION_MARKER = "ConfidentialPurchaseJustificationMarker";

    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String WORKER_PASSWORD = UUID.randomUUID().toString();
    private static final String KEYCLOAK_PASSWORD = UUID.randomUUID().toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final PostgreSQLContainer DATABASE;
    private static final GenericContainer<?> MAILPIT;
    private static final JdbcTemplate OWNER;
    private static final JdbcTemplate WORKER;

    static {
        Path repositoryRoot = repositoryRoot();
        DATABASE = new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")))
                .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                .withEnv("MIGRATION_DB_PASSWORD", MIGRATION_PASSWORD)
                .withEnv("WORKER_DB_PASSWORD", WORKER_PASSWORD)
                .withEnv("KEYCLOAK_DB_PASSWORD", KEYCLOAK_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(
                        repositoryRoot.resolve("infra/postgres/init-databases.sh")),
                        "/docker-entrypoint-initdb.d/10-procureflow.sh");
        DATABASE.start();
        MAILPIT = new GenericContainer<>(DockerImageName.parse(System.getProperty("mailpit.image")))
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/readyz").forPort(8025).withStartupTimeout(Duration.ofSeconds(60)));
        MAILPIT.start();
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), "procureflow_migrator", MIGRATION_PASSWORD)
                .locations("filesystem:" + repositoryRoot.resolve("backend/app/src/main/resources/db/migration"))
                .load()
                .migrate();
        OWNER = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword()));
        WORKER = new JdbcTemplate(workerDataSource());
    }

    private EmailServiceFixture() {}

    public static String jdbcUrl() {
        return DATABASE.getJdbcUrl();
    }

    public static String workerPassword() {
        return WORKER_PASSWORD;
    }

    public static DriverManagerDataSource workerDataSource() {
        return new DriverManagerDataSource(jdbcUrl(), WORKER_USER, WORKER_PASSWORD);
    }

    public static JdbcTemplate owner() {
        return OWNER;
    }

    public static JdbcTemplate worker() {
        return WORKER;
    }

    public static Connection workerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), WORKER_USER, WORKER_PASSWORD);
    }

    public static String mailpitHost() {
        return MAILPIT.getHost();
    }

    public static int mailpitPort() {
        return MAILPIT.getMappedPort(1025);
    }

    /** A loopback port that is currently closed, for real connection-refused behavior. */
    public static int closedLoopbackPort() {
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reserve a closed loopback port", e);
        }
    }

    /** Removes all fixture rows and captured mail so each test observes only its own effects. */
    public static void reset() {
        for (String table : new String[]{
                "notification_diagnostic", "email_job", "in_app_notification", "notification_delivery",
                "notification_inbox", "outbox_dispatch", "outbox_recipient", "outbox_event", "api_command",
                "request_history", "audit_entry", "approval_step", "budget_charge", "purchase_request",
                "membership_role", "manager_department", "monthly_budget", "membership", "app_identity",
                "department", "company"}) {
            OWNER.update("DELETE FROM " + table);
        }
        clearMailpit();
    }

    /**
     * Seeds one canonical event plus a MATERIALIZED EMAIL delivery and its PENDING email job. The
     * canonical rows use the owner connection; the delivery/job rows use the worker login.
     */
    public static SeededJob seedJob(boolean activeMembership) {
        UUID company = UUID.randomUUID();
        UUID department = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String envelope = """
                {"eventId":"%s","schemaVersion":1,"type":"%s","companyId":"%s","requestId":"%s","correlationId":"%s","occurredAt":"%s","payload":{"fromStatus":"DRAFT","toStatus":"AWAITING_DEPARTMENT_APPROVAL","requestVersion":1,"actorIdentityId":"%s"},"recipients":[{"identityId":"%s","membershipId":"%s","channels":["IN_APP","EMAIL"]}]}"""
                .formatted(event, DEFAULT_EVENT_TYPE, company, request, correlation, now, identity, identity,
                        membership);
        OWNER.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                company, "email-" + company, "Email fixture", Timestamp.from(now));
        OWNER.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, 'Engineering')",
                department, company);
        OWNER.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', ?)",
                identity, identity.toString());
        OWNER.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                + "VALUES (?, ?, ?, ?, ?)", membership, company, identity, department, activeMembership);
        OWNER.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id,
                department_id, title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 9876.54, ?, ?, ?)
                """, request, company, identity, membership, department, TITLE_MARKER, JUSTIFICATION_MARKER,
                Timestamp.from(now), Timestamp.from(now), correlation);
        OWNER.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id,
                occurred_at, envelope) VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, event, company, request, DEFAULT_EVENT_TYPE, correlation, Timestamp.from(now), envelope);
        OWNER.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) "
                + "VALUES (?, ?, ?, ?)", company, event, identity, membership);
        UUID delivery = UUID.randomUUID();
        WORKER.update("INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, "
                + "channel, outcome) VALUES (?, ?, ?, ?, ?, 'EMAIL', 'MATERIALIZED')",
                delivery, company, event, identity, membership);
        UUID job = UUID.randomUUID();
        WORKER.update("INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", job, delivery, company, event, identity, membership);
        return new SeededJob(company, department, identity, membership, request, correlation, event, delivery, job);
    }

    /** Additional MATERIALIZED IN_APP notification used to observe unrelated read state. */
    public static UUID seedInAppNotification(SeededJob seeded) {
        UUID delivery = UUID.randomUUID();
        WORKER.update("INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, "
                + "channel, outcome) VALUES (?, ?, ?, ?, ?, 'IN_APP', 'MATERIALIZED')",
                delivery, seeded.company(), seeded.event(), seeded.identity(), seeded.membership());
        UUID notification = UUID.randomUUID();
        WORKER.update("INSERT INTO in_app_notification (id, delivery_id, company_id, event_id, identity_id, "
                + "membership_id) VALUES (?, ?, ?, ?, ?, ?)",
                notification, delivery, seeded.company(), seeded.event(), seeded.identity(), seeded.membership());
        return notification;
    }

    public static void deactivateMembership(UUID membership) {
        OWNER.update("UPDATE membership SET active = false WHERE id = ?", membership);
    }

    public static void expireLease(UUID jobId) {
        WORKER.update("UPDATE email_job SET lease_until = now() - interval '1 second' WHERE id = ?", jobId);
    }

    public static void makeDue(UUID jobId) {
        WORKER.update("UPDATE email_job SET next_attempt_at = now() - interval '1 second' WHERE id = ?", jobId);
    }

    /** Forces a crash-loop row without simulating the crash through the runner. */
    public static void exhaust(UUID jobId, int attempts) {
        WORKER.update("UPDATE email_job SET state = 'PENDING', attempts = ?, next_attempt_at = now() - interval '1 second' "
                + "WHERE id = ?", attempts, jobId);
    }

    public static JobRow job(UUID jobId) {
        return WORKER.queryForObject("""
                SELECT delivery_id, state, attempts, lease_token, lease_until, sent_at, error_code, next_attempt_at,
                       recipient_address
                FROM email_job WHERE id = ?
                """, (rows, index) -> new JobRow(
                rows.getObject("delivery_id", UUID.class),
                rows.getString("state"),
                rows.getInt("attempts"),
                rows.getObject("lease_token", UUID.class),
                rows.getTimestamp("lease_until"),
                rows.getTimestamp("sent_at"),
                rows.getString("error_code"),
                rows.getTimestamp("next_attempt_at"),
                rows.getString("recipient_address")), jobId);
    }

    public static DeliveryRow delivery(UUID deliveryId) {
        return WORKER.queryForObject("""
                SELECT channel, outcome, reason_code FROM notification_delivery WHERE id = ?
                """, (rows, index) -> new DeliveryRow(rows.getString("channel"), rows.getString("outcome"),
                rows.getString("reason_code")), deliveryId);
    }

    public static Timestamp inAppReadAt(UUID notificationId) {
        return WORKER.queryForObject("SELECT read_at FROM in_app_notification WHERE id = ?",
                Timestamp.class, notificationId);
    }

    /** Immutable snapshot of the seeded purchase row to prove failures cannot modify it. */
    public static PurchaseRow purchase(UUID requestId) {
        return OWNER.queryForObject("""
                SELECT title, justification, amount, status, version FROM purchase_request WHERE id = ?
                """, (rows, index) -> new PurchaseRow(rows.getString("title"), rows.getString("justification"),
                rows.getBigDecimal("amount"), rows.getString("status"), rows.getLong("version")), requestId);
    }

    public static long budgetChargeCount(UUID companyId) {
        Long count = OWNER.queryForObject("SELECT count(*) FROM budget_charge WHERE company_id = ?",
                Long.class, companyId);
        return count == null ? 0 : count;
    }

    public static long auditCount(UUID companyId) {
        Long count = OWNER.queryForObject("SELECT count(*) FROM audit_entry WHERE company_id = ?",
                Long.class, companyId);
        return count == null ? 0 : count;
    }

    public static long mailpitTotal() {
        return mailpit("GET", "/api/v1/messages").path("total").asLong();
    }

    public static void clearMailpit() {
        mailpit("DELETE", "/api/v1/messages");
    }

    /** Lists captured message summaries in Mailpit's arrival order. */
    public static java.util.List<JsonNode> mailpitMessages() {
        JsonNode messages = mailpit("GET", "/api/v1/messages").path("messages");
        java.util.List<JsonNode> result = new java.util.ArrayList<>();
        messages.forEach(result::add);
        return result;
    }

    /** Header map for one captured message; Mailpit canonicalizes header name casing. */
    public static JsonNode mailpitHeaders(String messageId) {
        return mailpit("GET", "/api/v1/message/" + messageId + "/headers");
    }

    public static String mailpitRaw(String messageId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(mailpitBase() + "/api/v1/message/" + messageId + "/raw"))
                .GET().build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Mailpit raw fetch returned " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Mailpit raw fetch failed", e);
        }
    }

    public static String header(JsonNode headers, String name) {
        for (String field : headers.propertyNames()) {
            if (field.equalsIgnoreCase(name)) {
                return headers.path(field).isArray() ? headers.path(field).get(0).asText() : headers.path(field).asText();
            }
        }
        return null;
    }

    private static String mailpitBase() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    private static JsonNode mailpit(String method, String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(mailpitBase() + path));
        request = switch (method) {
            case "GET" -> request.GET();
            case "DELETE" -> request.DELETE();
            default -> throw new IllegalArgumentException("Unsupported Mailpit fixture method " + method);
        };
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Mailpit API " + method + " " + path
                        + " returned " + response.statusCode());
            }
            if ("DELETE".equals(method) || response.body().isBlank()) {
                return JSON.nullNode();
            }
            return JSON.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Mailpit API " + method + " " + path + " failed", e);
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("infra/postgres/init-databases.sh"))) {
            root = root.getParent();
            if (root == null) {
                throw new IllegalStateException("Cannot find repository root from the working directory");
            }
        }
        return root;
    }

    public record SeededJob(UUID company, UUID department, UUID identity, UUID membership, UUID request,
                            UUID correlation, UUID event, UUID delivery, UUID job) {

        public String recipientAddress() {
            return identity + "@demo.invalid";
        }

        public String envelopeRecipient() {
            return identity.toString();
        }
    }

    public record JobRow(UUID deliveryId, String state, int attempts, UUID leaseToken, Timestamp leaseUntil,
                         Timestamp sentAt, String errorCode, Timestamp nextAttemptAt, String recipientAddress) {}

    public record DeliveryRow(String channel, String outcome, String reasonCode) {}

    public record PurchaseRow(String title, String justification, java.math.BigDecimal amount, String status,
                              long version) {}
}

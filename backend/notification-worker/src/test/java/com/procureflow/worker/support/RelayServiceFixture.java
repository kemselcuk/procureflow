package com.procureflow.worker.support;

import com.procureflow.worker.config.RabbitTopology;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL 18.6 and RabbitMQ 4.3.6 fixtures. The worker test module applies the frozen
 * application migrations as files (never as a dependency) to a disposable database and uses only
 * worker credentials for relay operations.
 */
public final class RelayServiceFixture {
    public static final String WORKER_USER = "procureflow_worker";
    public static final String RABBIT_USER = "procureflow";

    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String WORKER_PASSWORD = UUID.randomUUID().toString();
    private static final String RABBIT_PASSWORD = UUID.randomUUID().toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final PostgreSQLContainer DATABASE;
    private static final RabbitMQContainer RABBIT;
    private static final JdbcTemplate OWNER;
    private static final JdbcTemplate WORKER;

    static {
        Path repositoryRoot = repositoryRoot();
        DATABASE = new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")))
                .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                .withEnv("MIGRATION_DB_PASSWORD", MIGRATION_PASSWORD)
                .withEnv("WORKER_DB_PASSWORD", WORKER_PASSWORD)
                .withEnv("KEYCLOAK_DB_PASSWORD", UUID.randomUUID().toString())
                .withCopyFileToContainer(MountableFile.forHostPath(
                        repositoryRoot.resolve("infra/postgres/init-databases.sh")),
                        "/docker-entrypoint-initdb.d/10-procureflow.sh");
        DATABASE.start();
        // Fixed loopback host bindings survive a container restart, unlike Docker-assigned random
        // ports, which the local engine reassigns; the restart tests need a stable client target.
        int amqpHostPort = freeLoopbackPort();
        int managementHostPort = freeLoopbackPort();
        RABBIT = new RabbitMQContainer(DockerImageName.parse(System.getProperty("rabbitmq.image"))
                .asCompatibleSubstituteFor("rabbitmq"))
                .withAdminUser(RABBIT_USER)
                .withAdminPassword(RABBIT_PASSWORD)
                .withCreateContainerCmdModifier(command -> {
                    HostConfig hostConfig = command.getHostConfig() == null
                            ? new HostConfig() : command.getHostConfig();
                    var bindings = new Ports();
                    if (hostConfig.getPortBindings() != null) {
                        hostConfig.getPortBindings().getBindings().forEach((port, hostBindings) -> {
                            for (Ports.Binding binding : hostBindings) {
                                bindings.bind(port, binding);
                            }
                        });
                    }
                    bindings.bind(ExposedPort.tcp(5672), Ports.Binding.bindIpAndPort("127.0.0.1", amqpHostPort));
                    bindings.bind(ExposedPort.tcp(15672),
                            Ports.Binding.bindIpAndPort("127.0.0.1", managementHostPort));
                    command.withHostConfig(hostConfig.withPortBindings(bindings));
                });
        RABBIT.start();
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), "procureflow_migrator", MIGRATION_PASSWORD)
                .locations("filesystem:" + repositoryRoot.resolve("backend/app/src/main/resources/db/migration"))
                .load()
                .migrate();
        OWNER = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword()));
        WORKER = new JdbcTemplate(workerDataSource());
        awaitManagementApi();
    }

    private RelayServiceFixture() {}

    public static String jdbcUrl() {
        return DATABASE.getJdbcUrl();
    }

    public static String workerPassword() {
        return WORKER_PASSWORD;
    }

    public static String rabbitHost() {
        return RABBIT.getHost();
    }

    public static int rabbitPort() {
        return RABBIT.getAmqpPort();
    }

    public static String rabbitPassword() {
        return RABBIT_PASSWORD;
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

    public static com.rabbitmq.client.Connection rabbitConnection() throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(rabbitHost());
        factory.setPort(rabbitPort());
        factory.setUsername(RABBIT_USER);
        factory.setPassword(RABBIT_PASSWORD);
        return factory.newConnection();
    }

    /** Removes all fixture rows so each test observes only its own events. */
    public static void reset() {
        for (String table : new String[]{
                "notification_diagnostic", "email_job", "in_app_notification", "notification_delivery",
                "notification_inbox", "outbox_dispatch", "outbox_recipient", "outbox_event", "api_command",
                "request_history", "audit_entry", "approval_step", "budget_charge", "purchase_request",
                "membership_role", "manager_department", "monthly_budget", "membership", "app_identity",
                "department", "company"}) {
            OWNER.update("DELETE FROM " + table);
        }
        try (var connection = rabbitConnection(); var channel = connection.createChannel()) {
            channel.queuePurge(RabbitTopology.QUEUE);
        } catch (Exception e) {
            throw new IllegalStateException("Could not purge the notification queue", e);
        }
    }

    /** Seeds a committed event through the schema's producer path; the V5 trigger creates dispatch. */
    public static SeededEvent seedEvent() {
        UUID company = UUID.randomUUID();
        UUID department = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String type = "request.submitted";
        String envelope = """
                {"eventId":"%s","schemaVersion":1,"type":"%s","companyId":"%s","requestId":"%s","correlationId":"%s","occurredAt":"%s","payload":{"fromStatus":"DRAFT","toStatus":"AWAITING_DEPARTMENT_APPROVAL","requestVersion":1,"actorIdentityId":"%s"},"recipients":[{"identityId":"%s","membershipId":"%s","channels":["IN_APP","EMAIL"]}]}"""
                .formatted(event, type, company, request, correlation, now, identity, identity, membership);
        OWNER.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                company, "relay-" + company, "Relay fixture", Timestamp.from(now));
        OWNER.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, 'Engineering')",
                department, company);
        OWNER.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', ?)",
                identity, identity.toString());
        OWNER.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                + "VALUES (?, ?, ?, ?, true)", membership, company, identity, department);
        OWNER.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id,
                department_id, title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'Relay fixture request', 'Fixture only', 10.00, ?, ?, ?)
                """, request, company, identity, membership, department, Timestamp.from(now), Timestamp.from(now),
                correlation);
        OWNER.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id,
                occurred_at, envelope) VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, event, company, request, type, correlation, Timestamp.from(now), envelope);
        OWNER.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) "
                + "VALUES (?, ?, ?, ?)", company, event, identity, membership);
        String storedEnvelope = OWNER.queryForObject("SELECT envelope::text FROM outbox_event WHERE id = ?",
                String.class, event);
        return new SeededEvent(company, department, identity, membership, request, correlation, event, type,
                storedEnvelope);
    }

    public static DispatchRow dispatch(UUID eventId) {
        return WORKER.queryForObject("""
                SELECT state, attempts, lease_token, lease_until, published_at, error_code, next_attempt_at
                FROM outbox_dispatch WHERE event_id = ?
                """, (rows, index) -> new DispatchRow(
                rows.getString("state"),
                rows.getInt("attempts"),
                rows.getObject("lease_token", UUID.class),
                rows.getTimestamp("lease_until"),
                rows.getTimestamp("published_at"),
                rows.getString("error_code"),
                rows.getTimestamp("next_attempt_at")), eventId);
    }

    public static void expireLease(UUID eventId) {
        WORKER.update("UPDATE outbox_dispatch SET lease_until = now() - interval '1 second' WHERE event_id = ?",
                eventId);
    }

    public static long messageCount() throws Exception {
        try (var connection = rabbitConnection(); var channel = connection.createChannel()) {
            return channel.messageCount(RabbitTopology.QUEUE);
        }
    }

    public static JsonNode management(String method, String path) {
        String url = RABBIT.getHttpUrl() + "/api" + path;
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (RABBIT_USER + ":" + RABBIT_PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json");
        request = switch (method) {
            case "GET" -> request.GET();
            case "DELETE" -> request.DELETE();
            default -> throw new IllegalArgumentException("Unsupported fixture HTTP method " + method);
        };
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Rabbit management API " + method + " " + path
                        + " returned " + response.statusCode());
            }
            return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Rabbit management API " + method + " " + path + " failed", e);
        }
    }

    public static void stopBroker() {
        RABBIT.getDockerClient().stopContainerCmd(RABBIT.getContainerId()).withTimeout(30).exec();
    }

    public static void startBroker() {
        RABBIT.getDockerClient().startContainerCmd(RABBIT.getContainerId()).exec();
        awaitManagementApi();
    }

    public static void restartBroker() {
        stopBroker();
        startBroker();
    }

    public static void pauseBroker() {
        RABBIT.getDockerClient().pauseContainerCmd(RABBIT.getContainerId()).exec();
    }

    public static void unpauseBroker() {
        RABBIT.getDockerClient().unpauseContainerCmd(RABBIT.getContainerId()).exec();
    }

    public static void awaitManagementApi() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                management("GET", "/overview");
                return;
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for RabbitMQ", e);
            }
        }
        throw new IllegalStateException("RabbitMQ management API did not become ready", last);
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

    private static int freeLoopbackPort() {
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reserve a loopback port for the RabbitMQ fixture", e);
        }
    }

    public record SeededEvent(UUID company, UUID department, UUID identity, UUID membership, UUID request,
                              UUID correlation, UUID event, String type, String envelope) {}

    public record DispatchRow(String state, int attempts, UUID leaseToken, Timestamp leaseUntil,
                              Timestamp publishedAt, String errorCode, Timestamp nextAttemptAt) {}
}

package com.procureflow.worker.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.procureflow.worker.config.RabbitTopology;
import com.rabbitmq.client.AMQP;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-Docker broker recreation evidence for P8.3b.
 *
 * <p>The negative control retains a named volume but restarts RabbitMQ under a different node
 * identity: the durable queue is no longer visible, which is the failure mode the fixed identity
 * prevents. The positive case uses the same fixed hostname/node name as {@code compose.yaml}, removes
 * and recreates the broker on the retained volume, proves the persistent queued envelope is still
 * there, reconnects the real worker consumer and observes the queue drain. It then intentionally
 * replaces the broker with an empty volume, runs the reviewed company/event-scoped replay SQL for one
 * previously PUBLISHED event, and proves the real relay/consumer redelivery is deduplicated while an
 * unrelated company's event and effects stay untouched.
 *
 * <p>Every container, network port and named volume is owned by this test and carries a unique
 * prefix; no existing Compose project, volume or historical fixture is inspected or reused.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrokerRecreationReplayIT {
    private static final String RABBIT_HOSTNAME = "procureflow-rabbitmq";
    private static final String RABBIT_NODENAME = "rabbit@" + RABBIT_HOSTNAME;
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String PREFIX = "procureflow-p83b-" + RUN_ID;
    private static final String CONTEXT_VOLUME = PREFIX + "-context";
    private static final String REPLACEMENT_VOLUME = PREFIX + "-replacement";
    private static final String NEGATIVE_VOLUME = PREFIX + "-negative";
    private static final String RABBIT_USER = "procureflow";
    private static final String RABBIT_PASSWORD = UUID.randomUUID().toString();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String WORKER_PASSWORD = UUID.randomUUID().toString();
    private static final Duration AWAIT = Duration.ofSeconds(60);

    private static final DockerClient DOCKER = DockerClientFactory.instance().client();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CONTEXT_AMQP_PORT = freeLoopbackPort();
    private static final int CONTEXT_MANAGEMENT_PORT = freeLoopbackPort();

    private static PostgreSQLContainer database;
    private static JdbcTemplate owner;
    private static JdbcTemplate worker;
    private static RabbitMQContainer contextBroker;

    @Autowired
    private NotificationOutboxRelay relay;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    static {
        startFixture();
    }

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> database.getJdbcUrl());
        registry.add("spring.datasource.username", () -> "procureflow_worker");
        registry.add("spring.datasource.password", () -> WORKER_PASSWORD);
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> CONTEXT_AMQP_PORT);
        registry.add("spring.rabbitmq.username", () -> RABBIT_USER);
        registry.add("spring.rabbitmq.password", () -> RABBIT_PASSWORD);
        registry.add("worker.relay.scheduled", () -> "false");
        registry.add("worker.relay.batch-size", () -> "10");
        registry.add("worker.relay.max-inflight", () -> "40");
        registry.add("worker.relay.lease-timeout", () -> "PT30S");
        registry.add("worker.relay.confirm-timeout", () -> "PT5S");
        registry.add("worker.relay.max-attempts", () -> "5");
        registry.add("worker.relay.retry-backoff", () -> "PT0.5S");
        registry.add("worker.relay.retry-backoff-multiplier", () -> "2.0");
        registry.add("worker.relay.retry-backoff-max", () -> "PT2S");
        registry.add("worker.relay.poll-interval", () -> "PT1H");
        registry.add("worker.consumer.enabled", () -> "true");
        registry.add("worker.consumer.prefetch-count", () -> "4");
        registry.add("worker.consumer.concurrent-consumers", () -> "2");
        registry.add("worker.consumer.max-concurrent-consumers", () -> "2");
        registry.add("worker.consumer.max-attempts", () -> "5");
        registry.add("worker.consumer.retry-backoff", () -> "PT0.4S");
        registry.add("worker.consumer.retry-backoff-multiplier", () -> "2.0");
        registry.add("worker.consumer.retry-backoff-max", () -> "PT1S");
        registry.add("worker.consumer.max-body-bytes", () -> "65536");
        registry.add("worker.consumer.shutdown-timeout", () -> "PT5S");
        registry.add("worker.smtp.enabled", () -> "false");
    }

    @Test
    @Order(1)
    void changingNodeIdentityOnARetainedVolumeLosesTheQueuedMessage() throws Exception {
        String firstHost = "procureflow-rabbitmq-negative-a";
        String secondHost = "procureflow-rabbitmq-negative-b";
        int amqpPort = freeLoopbackPort();
        int managementPort = freeLoopbackPort();
        createVolume(NEGATIVE_VOLUME);
        RabbitMQContainer first = null;
        RabbitMQContainer second = null;
        try {
            first = startBroker(firstHost, "rabbit@" + firstHost, NEGATIVE_VOLUME, amqpPort, managementPort);
            assertThat(nodeName(managementPort)).isEqualTo("rabbit@" + firstHost);
            publishPersistent(amqpPort, UUID.randomUUID());
            await("negative-control message queued", AWAIT,
                    () -> messagesReady(managementPort) == 1);

            removeBroker(first);
            first = null;
            second = startBroker(secondHost, "rabbit@" + secondHost, NEGATIVE_VOLUME, amqpPort,
                    managementPort);
            assertThat(nodeName(managementPort)).isEqualTo("rabbit@" + secondHost);
            assertThat(queueStatus(managementPort)).as("a changed node identity must not open the old "
                    + "node's durable queue, even when the volume is retained").isEqualTo(404);
        } finally {
            removeBroker(first);
            removeBroker(second);
            removeVolume(NEGATIVE_VOLUME);
        }
    }

    @Test
    @Order(2)
    void fixedIdentityRetainsQueuedMessageAndScopedReplayAfterReplacement() throws Exception {
        assertThat(nodeName(CONTEXT_MANAGEMENT_PORT)).isEqualTo(RABBIT_NODENAME);
        assertThat(System.getProperty("rabbitmq.image")).contains("@sha256:");

        // Phase 1: a persistent message is queued while the consumer is stopped, then the broker
        // container is removed and recreated on the same volume with the fixed node identity.
        stopListener();
        SeededEvent queued = seedEvent();
        relay.runOnce();
        await("relay publication", AWAIT, () -> dispatchState(queued.event()).startsWith("PUBLISHED|"));
        await("durable broker message before recreation", AWAIT,
                () -> messagesReady(CONTEXT_MANAGEMENT_PORT) == 1);
        assertQueuedMessage(queued);

        removeBroker(contextBroker);
        contextBroker = startBroker(RABBIT_HOSTNAME, RABBIT_NODENAME, CONTEXT_VOLUME,
                CONTEXT_AMQP_PORT, CONTEXT_MANAGEMENT_PORT);
        assertThat(nodeName(CONTEXT_MANAGEMENT_PORT)).isEqualTo(RABBIT_NODENAME);
        await("durable broker message after recreation", AWAIT,
                () -> messagesReady(CONTEXT_MANAGEMENT_PORT) == 1);
        assertQueuedMessage(queued);

        startListener();
        await("consumer reconnect materialization", AWAIT,
                () -> !inboxState(queued.company(), queued.event()).isEmpty());
        await("queue drain after the consumer reconnect", AWAIT, () -> queueDepth().drained());
        assertThat(effectState(queued.company(), queued.event()))
                .contains("deliveries=4").contains("inApp=1").contains("email=1")
                .contains("SKIPPED:membership_inactive");

        // Phase 2: two already-consumed PUBLISHED events in different companies. The broker is then
        // intentionally replaced by an empty volume; the scoped replay must re-drive only event A.
        SeededEvent selected = seedEvent();
        SeededEvent unrelated = seedEvent();
        assertThat(selected.company()).isNotEqualTo(unrelated.company());
        relay.runOnce();
        await("selected event consumed", AWAIT,
                () -> !inboxState(selected.company(), selected.event()).isEmpty());
        await("unrelated event consumed", AWAIT,
                () -> !inboxState(unrelated.company(), unrelated.event()).isEmpty());
        await("initial replay-phase queue drain", AWAIT, () -> queueDepth().drained());
        assertThat(dispatchState(selected.event())).startsWith("PUBLISHED|");
        assertThat(dispatchState(unrelated.event())).startsWith("PUBLISHED|");

        markReadAndSent(selected);
        String selectedEffectsBefore = effectState(selected.company(), selected.event());
        assertThat(selectedEffectsBefore).contains("SKIPPED:membership_inactive");
        String unrelatedEffectsBefore = effectState(unrelated.company(), unrelated.event());
        String unrelatedDispatchBefore = dispatchState(unrelated.event());

        stopListener();
        removeBroker(contextBroker);
        contextBroker = null;
        removeVolume(CONTEXT_VOLUME);
        createVolume(REPLACEMENT_VOLUME);
        contextBroker = startBroker(RABBIT_HOSTNAME, RABBIT_NODENAME, REPLACEMENT_VOLUME,
                CONTEXT_AMQP_PORT, CONTEXT_MANAGEMENT_PORT);
        assertThat(nodeName(CONTEXT_MANAGEMENT_PORT)).isEqualTo(RABBIT_NODENAME);
        startListener();
        await("topology redeclared on the replacement broker", AWAIT,
                () -> queueStatus(CONTEXT_MANAGEMENT_PORT) == 200);
        await("empty replacement queue", AWAIT, () -> queueDepth().drained());

        // A different company/event pairing cannot select the row, and leaves both records intact.
        assertThatThrownBy(() -> executeReplay(selected.company(), unrelated.event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay refused");
        assertThat(dispatchState(selected.event())).startsWith("PUBLISHED|");
        assertThat(dispatchState(unrelated.event())).isEqualTo(unrelatedDispatchBefore);
        assertThat(effectState(unrelated.company(), unrelated.event())).isEqualTo(unrelatedEffectsBefore);

        executeReplay(selected.company(), selected.event());
        assertThat(dispatchState(selected.event())).startsWith("PENDING|0|");
        assertThat(dispatchState(unrelated.event())).isEqualTo(unrelatedDispatchBefore);

        long deliveriesBefore = wireDeliveries();
        await("scoped replay republication and duplicate consumption", AWAIT, () -> {
            relay.runOnce();
            return dispatchState(selected.event()).startsWith("PUBLISHED|")
                    && wireDeliveries() > deliveriesBefore;
        });
        await("replayed delivery acknowledged", AWAIT, () -> queueDepth().drained());

        assertThat(effectState(selected.company(), selected.event()))
                .as("duplicate delivery must not add a second inbox, in-app or email effect")
                .isEqualTo(selectedEffectsBefore);
        assertThat(dispatchState(unrelated.event())).isEqualTo(unrelatedDispatchBefore);
        assertThat(effectState(unrelated.company(), unrelated.event())).isEqualTo(unrelatedEffectsBefore);
        assertThat(queueDepth().drained()).isTrue();
    }

    @AfterAll
    void cleanupOwnedResources() {
        stopListenerQuietly();
        removeBroker(contextBroker);
        contextBroker = null;
        removeVolume(CONTEXT_VOLUME);
        removeVolume(REPLACEMENT_VOLUME);
        removeVolume(NEGATIVE_VOLUME);
        if (database != null) {
            database.stop();
        }
    }

    // ------------------------------------------------------------------ fixture setup

    private static synchronized void startFixture() {
        if (database != null) {
            return;
        }
        Path repositoryRoot = repositoryRoot();
        createVolume(CONTEXT_VOLUME);
        database = new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")))
                .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                .withEnv("MIGRATION_DB_PASSWORD", MIGRATION_PASSWORD)
                .withEnv("WORKER_DB_PASSWORD", WORKER_PASSWORD)
                .withEnv("KEYCLOAK_DB_PASSWORD", UUID.randomUUID().toString())
                .withCopyFileToContainer(MountableFile.forHostPath(
                        repositoryRoot.resolve("infra/postgres/init-databases.sh")),
                        "/docker-entrypoint-initdb.d/10-procureflow.sh");
        database.start();
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), "procureflow_migrator", MIGRATION_PASSWORD)
                .locations("filesystem:" + repositoryRoot.resolve("backend/app/src/main/resources/db/migration"))
                .load()
                .migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),
                database.getUsername(), database.getPassword()));
        worker = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(),
                "procureflow_worker", WORKER_PASSWORD));
        contextBroker = startBroker(RABBIT_HOSTNAME, RABBIT_NODENAME, CONTEXT_VOLUME,
                CONTEXT_AMQP_PORT, CONTEXT_MANAGEMENT_PORT);
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("infra/postgres/init-databases.sh"))) {
            root = root.getParent();
            if (root == null) {
                throw new IllegalStateException("Cannot find the repository root from the working directory");
            }
        }
        return root;
    }

    private static RabbitMQContainer startBroker(String hostname, String nodeName, String volumeName,
                                                 int amqpPort, int managementPort) {
        RabbitMQContainer broker = new RabbitMQContainer(
                DockerImageName.parse(System.getProperty("rabbitmq.image"))
                        .asCompatibleSubstituteFor("rabbitmq"))
                .withAdminUser(RABBIT_USER)
                .withAdminPassword(RABBIT_PASSWORD)
                .withEnv("RABBITMQ_NODENAME", nodeName)
                .withCreateContainerCmdModifier(command -> {
                    command.withHostName(hostname);
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
                    bindings.bind(ExposedPort.tcp(5672),
                            Ports.Binding.bindIpAndPort("127.0.0.1", amqpPort));
                    bindings.bind(ExposedPort.tcp(15672),
                            Ports.Binding.bindIpAndPort("127.0.0.1", managementPort));
                    command.withHostConfig(hostConfig
                            .withBinds(new Bind(volumeName, new Volume("/var/lib/rabbitmq")))
                            .withPortBindings(bindings));
                });
        broker.start();
        await("RabbitMQ management readiness", AWAIT, () -> {
            try {
                management(managementPort, "GET", "/overview", null);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
        return broker;
    }

    private static int freeLoopbackPort() {
        try (var socket = new java.net.ServerSocket(0, 0,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reserve a loopback port for the broker fixture", e);
        }
    }

    private static void createVolume(String name) {
        try {
            InspectVolumeResponse existing = DOCKER.inspectVolumeCmd(name).exec();
            if (existing != null) {
                return;
            }
        } catch (NotFoundException ignored) {
            // A fresh owned volume is created below.
        }
        DOCKER.createVolumeCmd().withName(name).exec();
    }

    private static void removeBroker(RabbitMQContainer broker) {
        if (broker == null) {
            return;
        }
        try {
            DOCKER.removeContainerCmd(broker.getContainerId()).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // The container was already removed by an earlier step.
        } finally {
            try {
                broker.stop();
            } catch (RuntimeException ignored) {
                // The removed container has no live process left to stop.
            }
        }
    }

    private static void removeVolume(String name) {
        try {
            DOCKER.removeVolumeCmd(name).exec();
        } catch (NotFoundException ignored) {
            // The volume was already removed or was never created.
        }
    }

    // ------------------------------------------------------------------ worker control

    private void stopListener() {
        var container = listenerRegistry.getListenerContainer("notification-queue-consumer");
        assertThat(container).as("the real notification consumer must be registered").isNotNull();
        container.stop();
    }

    private void startListener() {
        var container = listenerRegistry.getListenerContainer("notification-queue-consumer");
        assertThat(container).as("the real notification consumer must be registered").isNotNull();
        if (!container.isRunning()) {
            container.start();
        }
    }

    private void stopListenerQuietly() {
        var container = listenerRegistry.getListenerContainer("notification-queue-consumer");
        if (container != null) {
            try {
                container.stop();
            } catch (RuntimeException ignored) {
                // Cleanup only; an already closed connection is not a test failure.
            }
        }
    }

    // ------------------------------------------------------------------ canonical fixture rows

    private static SeededEvent seedEvent() {
        UUID company = UUID.randomUUID();
        UUID department = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID skippedIdentity = UUID.randomUUID();
        UUID skippedMembership = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String type = "request.submitted";
        String envelope = """
                {"eventId":"%s","schemaVersion":1,"type":"%s","companyId":"%s","requestId":"%s","correlationId":"%s","occurredAt":"%s","payload":{"fromStatus":"DRAFT","toStatus":"AWAITING_DEPARTMENT_APPROVAL","requestVersion":1,"actorIdentityId":"%s"},"recipients":[{"identityId":"%s","membershipId":"%s","channels":["IN_APP","EMAIL"]},{"identityId":"%s","membershipId":"%s","channels":["IN_APP","EMAIL"]}]}"""
                .formatted(event, type, company, request, correlation, now, identity, identity, membership,
                        skippedIdentity, skippedMembership);
        owner.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                company, "broker-recreation-" + company, "Broker recreation fixture", Timestamp.from(now));
        owner.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, 'Engineering')",
                department, company);
        owner.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', ?)",
                identity, identity.toString());
        owner.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', ?)",
                skippedIdentity, skippedIdentity.toString());
        owner.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                + "VALUES (?, ?, ?, ?, true)", membership, company, identity, department);
        owner.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                + "VALUES (?, ?, ?, ?, false)", skippedMembership, company, skippedIdentity, department);
        owner.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id,
                department_id, title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'Broker recreation request', 'Fixture only', 10.00, ?, ?, ?)
                """, request, company, identity, membership, department, Timestamp.from(now),
                Timestamp.from(now), correlation);
        owner.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id,
                occurred_at, envelope) VALUES (?, ?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, event, company, request, type, correlation, Timestamp.from(now), envelope);
        owner.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) "
                + "VALUES (?, ?, ?, ?), (?, ?, ?, ?)", company, event, identity, membership,
                company, event, skippedIdentity, skippedMembership);
        String storedEnvelope = owner.queryForObject(
                "SELECT envelope::text FROM outbox_event WHERE id = ?", String.class, event);
        return new SeededEvent(company, event, storedEnvelope);
    }

    private static void markReadAndSent(SeededEvent event) {
        owner.update("UPDATE in_app_notification SET read_at = now() "
                + "WHERE company_id = ? AND event_id = ?", event.company(), event.event());
        owner.update("UPDATE email_job SET state = 'SENT', sent_at = now() "
                + "WHERE company_id = ? AND event_id = ?", event.company(), event.event());
    }

    private static String dispatchState(UUID event) {
        return worker.queryForObject("""
                SELECT state || '|' || attempts || '|' || coalesce(published_at::text, '') || '|'
                       || coalesce(error_code, '')
                FROM outbox_dispatch WHERE event_id = ?
                """, String.class, event);
    }

    private static String inboxState(UUID company, UUID event) {
        return worker.queryForObject("""
                SELECT coalesce((SELECT processed_at::text FROM notification_inbox
                                 WHERE company_id = ? AND event_id = ?), '')
                """, String.class, company, event);
    }

    private static String effectState(UUID company, UUID event) {
        String deliveries = worker.queryForObject("""
                SELECT coalesce(string_agg(identity_id::text || ':' || channel || ':' || outcome || ':'
                       || coalesce(reason_code, ''), ',' ORDER BY identity_id, channel), '')
                FROM notification_delivery WHERE company_id = ? AND event_id = ?
                """, String.class, company, event);
        String inApp = worker.queryForObject("""
                SELECT coalesce(string_agg(id::text || ':' || coalesce(read_at::text, ''),
                       ',' ORDER BY id), '')
                FROM in_app_notification WHERE company_id = ? AND event_id = ?
                """, String.class, company, event);
        String email = worker.queryForObject("""
                SELECT coalesce(string_agg(id::text || ':' || state || ':'
                       || coalesce(sent_at::text, ''), ',' ORDER BY id), '')
                FROM email_job WHERE company_id = ? AND event_id = ?
                """, String.class, company, event);
        long deliveryCount = count("notification_delivery", company, event);
        long inAppCount = count("in_app_notification", company, event);
        long emailCount = count("email_job", company, event);
        long inboxCount = count("notification_inbox", company, event);
        return "deliveries=" + deliveryCount + "[" + deliveries + "]" + "|inApp=" + inAppCount
                + "[" + inApp + "]" + "|email=" + emailCount + "[" + email + "]"
                + "|inbox=" + inboxCount + "[" + inboxState(company, event) + "]";
    }

    private static long count(String table, UUID company, UUID event) {
        Long value = worker.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE company_id = ? AND event_id = ?",
                Long.class, company, event);
        return value == null ? 0 : value;
    }

    private static void executeReplay(UUID company, UUID event) {
        String sql = replaySql()
                .replace("<company-id>", company.toString())
                .replace("<event-id>", event.toString());
        DriverManagerDataSource dataSource = (DriverManagerDataSource) owner.getDataSource();
        String url = dataSource.getUrl();
        if (url != null && !url.contains("preferQueryMode=")) {
            url = url + (url.contains("?") ? "&" : "?") + "preferQueryMode=simple";
        }
        try (Connection connection = DriverManager.getConnection(url, dataSource.getUsername(),
                dataSource.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static String replaySql() {
        try {
            return Files.readString(repositoryRoot().resolve("scripts/replay_notification_event.sql"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read scripts/replay_notification_event.sql", e);
        }
    }

    // ------------------------------------------------------------------ broker observations

    private static void assertQueuedMessage(SeededEvent event) {
        JsonNode messages = management(CONTEXT_MANAGEMENT_PORT, "POST",
                "/queues/%2F/" + RabbitTopology.QUEUE + "/get",
                "{\"count\":1,\"ackmode\":\"reject_requeue_true\",\"encoding\":\"auto\"}");
        assertThat(messages.size()).isEqualTo(1);
        JsonNode message = messages.get(0);
        assertThat(message.get("properties").get("delivery_mode").asInt())
                .as("the queued message must remain persistent after broker recreation").isEqualTo(2);
        assertThat(message.get("properties").get("message_id").asString())
                .isEqualTo(event.event().toString());
        assertThat(message.get("payload").asString()).isEqualTo(event.envelope());
    }

    private static void publishPersistent(int amqpPort, UUID messageId) throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(amqpPort);
        factory.setUsername(RABBIT_USER);
        factory.setPassword(RABBIT_PASSWORD);
        try (var connection = factory.newConnection();
             var channel = connection.createChannel()) {
            channel.exchangeDeclare(RabbitTopology.EXCHANGE, "topic", true, false, null);
            channel.queueDeclare(RabbitTopology.QUEUE, true, false, false,
                    Map.of("x-queue-type", "classic"));
            channel.queueBind(RabbitTopology.QUEUE, RabbitTopology.EXCHANGE,
                    RabbitTopology.BINDING_PATTERN);
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2)
                    .contentType("application/json")
                    .messageId(messageId.toString())
                    .type("request.submitted")
                    .headers(Map.<String, Object>of("schemaVersion", 1))
                    .build();
            channel.basicPublish(RabbitTopology.EXCHANGE, "request.test", properties,
                    "{\"probe\":\"broker-recreation\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String nodeName(int managementPort) {
        JsonNode nodes = management(managementPort, "GET", "/nodes", null);
        assertThat(nodes.isArray()).isTrue();
        assertThat(nodes.size()).isGreaterThan(0);
        return nodes.get(0).get("name").asString();
    }

    private static int queueStatus(int managementPort) {
        return managementStatus(managementPort, "GET", "/queues/%2F/" + RabbitTopology.QUEUE);
    }

    private static int messagesReady(int managementPort) {
        return management(managementPort, "GET", "/queues/%2F/" + RabbitTopology.QUEUE, null)
                .get("messages_ready").asInt();
    }

    private static QueueDepth queueDepth() {
        JsonNode queue = management(CONTEXT_MANAGEMENT_PORT, "GET",
                "/queues/%2F/" + RabbitTopology.QUEUE, null);
        return new QueueDepth(queue.get("messages_ready").asInt(),
                queue.get("messages_unacknowledged").asInt());
    }

    private static long wireDeliveries() {
        JsonNode queue = management(CONTEXT_MANAGEMENT_PORT, "GET",
                "/queues/%2F/" + RabbitTopology.QUEUE, null);
        JsonNode stats = queue.get("message_stats");
        JsonNode deliverGet = stats == null ? null : stats.get("deliver_get");
        return deliverGet == null ? 0 : deliverGet.asLong();
    }

    private static JsonNode management(int port, String method, String path, String body) {
        HttpResponse<String> response = request(port, method, path, body);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Rabbit management API " + method + " returned "
                    + response.statusCode());
        }
        try {
            return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Rabbit management API returned non-JSON data", e);
        }
    }

    private static int managementStatus(int port, String method, String path) {
        return request(port, method, path, null).statusCode();
    }

    private static HttpResponse<String> request(int port, String method, String path, String body) {
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (RABBIT_USER + ":" + RABBIT_PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api" + path))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json");
        builder = "GET".equals(method)
                ? builder.GET()
                : builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        try {
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Rabbit management API " + method + " failed", e);
        }
    }

    private static void await(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // A broker that is still starting or reconnecting is retried within the bound.
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out after " + timeout.toSeconds() + "s waiting for " + description);
    }

    private record SeededEvent(UUID company, UUID event, String envelope) {
    }

    private record QueueDepth(int ready, int unacknowledged) {
        boolean drained() {
            return ready == 0 && unacknowledged == 0;
        }
    }
}

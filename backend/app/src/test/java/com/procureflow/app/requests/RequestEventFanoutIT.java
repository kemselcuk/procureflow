package com.procureflow.app.requests;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.TestDatabase;
import com.procureflow.app.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Real-PostgreSQL fan-out acceptance for one business transition with more recipients than the
 * producer's batch size. The test drives the real submit command, then applies the actual worker
 * login and the same inbox/delivery/in-app/email-job statements the consumer uses. It is a bounded
 * consumer-equivalent, not a RabbitMQ delivery run: it proves the produced envelopes fit the
 * consumer body limit and that duplicate processing is still idempotent.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestEventFanoutIT {
    /** Worker default {@code worker.consumer.max-body-bytes}; a longer body is MALFORMED_MESSAGE. */
    static final int CONSUMER_MAX_BODY_BYTES = 65_536;
    /** Contract under test: the producer must stay far below the consumer limit. */
    static final int PRODUCER_ENVELOPE_BYTES = 16 * 1024;
    static final int PRODUCER_BATCH_SIZE = 40;
    /** 550 extra managers plus the seeded manager gives 551 captured recipients. */
    static final int EXTRA_MANAGERS = 550;

    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final TestTokens TOKENS = new TestTokens();
    static final JsonMapper JSON = JsonMapper.builder().build();

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
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword()));
        worker = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "procureflow_worker",
                TestDatabase.workerPassword()));
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

    @Test
    void largeManagerFanOutIsSplitAndEachIdentityGetsOneInAppAndEmailEffect() throws Exception {
        seedExtraManagers(EXTRA_MANAGERS);
        long expectedRecipients = EXTRA_MANAGERS + 1L;
        int expectedEvents = (int) ((expectedRecipients + PRODUCER_BATCH_SIZE - 1) / PRODUCER_BATCH_SIZE);

        var created = send("POST", "", content("50.00"), UUID.randomUUID());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var requestId = UUID.fromString(node(created).path("id").asText());

        var submitted = send("POST", "/" + requestId + "/submit", Map.of("version", 0), UUID.randomUUID());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
        var correlationId = UUID.fromString(node(submitted).path("correlationId").asText());

        List<StoredEvent> events = submittedEvents(requestId);
        assertThat(events).as("the transition must produce at least one canonical event").isNotEmpty();

        // Every stored envelope is the exact body the relay publishes; it must stay below both the
        // 16 KiB producer contract and the 64 KiB consumer hard limit. A regression to one unbounded
        // event fails here before any delivery assertions.
        int maxBodyBytes = 0;
        for (StoredEvent event : events) {
            int bodyBytes = event.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            maxBodyBytes = Math.max(maxBodyBytes, bodyBytes);
            assertThat(bodyBytes).as("event %s body must stay below the producer envelope contract", event.id())
                    .isLessThanOrEqualTo(PRODUCER_ENVELOPE_BYTES);
            assertThat(bodyBytes).as("event %s body must stay below the consumer limit", event.id())
                    .isLessThan(CONSUMER_MAX_BODY_BYTES);
            assertThat(event.envelope().path("recipients").size())
                    .as("event %s recipient batch size", event.id())
                    .isLessThanOrEqualTo(PRODUCER_BATCH_SIZE);
        }

        assertThat(events).as("one canonical event per bounded recipient batch").hasSize(expectedEvents);
        assertThat(events.stream().map(StoredEvent::id).distinct().count()).isEqualTo(expectedEvents);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM outbox_dispatch d JOIN outbox_event e ON e.id = d.event_id
                WHERE e.request_id = ? AND d.state = 'PENDING'
                """, Integer.class, requestId)).isEqualTo(expectedEvents);

        assertSameBusinessTransition(events, correlationId);
        assertRecipientPartition(requestId, expectedRecipients);

        // No message may be classified as MALFORMED; the fan-out must not add diagnostic rows.
        assertThat(owner.queryForObject("SELECT count(*) FROM notification_diagnostic", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM request_history WHERE request_id = ? AND action = 'request.submitted'",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE entity_id = ? AND action = 'request.submitted'",
                Integer.class, requestId)).isEqualTo(1);

        // First consume pass, then an exact duplicate pass for every event: the inbox marker must
        // suppress every second effect. This uses the real worker login and delivery constraints.
        assertThat(materializeOnce(events)).isEqualTo(expectedEvents);
        assertThat(materializeOnce(events)).as("all duplicate deliveries are suppressed").isZero();

        assertThat(owner.queryForObject("SELECT count(*) FROM notification_inbox WHERE company_id = ? AND event_id IN "
                + "(SELECT id FROM outbox_event WHERE request_id = ? AND event_type = 'request.submitted')",
                Integer.class, id("a"), requestId)).isEqualTo(expectedEvents);
        assertThat(owner.queryForObject("SELECT count(*) FROM notification_delivery WHERE company_id = ? AND event_id IN "
                + "(SELECT id FROM outbox_event WHERE request_id = ? AND event_type = 'request.submitted')",
                Integer.class, id("a"), requestId)).isEqualTo((int) expectedRecipients * 2);
        assertThat(owner.queryForObject("SELECT count(*) FROM in_app_notification WHERE company_id = ? AND event_id IN "
                + "(SELECT id FROM outbox_event WHERE request_id = ? AND event_type = 'request.submitted')",
                Integer.class, id("a"), requestId)).isEqualTo((int) expectedRecipients);
        assertThat(owner.queryForObject("SELECT count(*) FROM email_job WHERE company_id = ? AND event_id IN "
                + "(SELECT id FROM outbox_event WHERE request_id = ? AND event_type = 'request.submitted')",
                Integer.class, id("a"), requestId)).isEqualTo((int) expectedRecipients);
        assertThat(owner.queryForList("""
                SELECT identity_id FROM notification_delivery WHERE company_id = ? AND event_id IN
                    (SELECT id FROM outbox_event WHERE request_id = ? AND event_type = 'request.submitted')
                GROUP BY identity_id, channel HAVING count(*) <> 1
                """, UUID.class, id("a"), requestId)).isEmpty();
        assertThat(owner.queryForList("""
                SELECT identity_id FROM in_app_notification WHERE company_id = ? AND event_id IN
                    (SELECT id FROM outbox_event WHERE request_id = ? AND event_type = 'request.submitted')
                GROUP BY identity_id HAVING count(*) <> 1
                """, UUID.class, id("a"), requestId)).isEmpty();
        assertThat(owner.queryForList("""
                SELECT identity_id FROM email_job WHERE company_id = ? AND event_id IN
                    (SELECT id FROM outbox_event WHERE request_id = ? AND event_type = 'request.submitted')
                GROUP BY identity_id HAVING count(*) <> 1
                """, UUID.class, id("a"), requestId)).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM notification_diagnostic", Integer.class)).isZero();

        System.out.printf("FANOUT evidence: recipients=%d events=%d batchSize=%d maxBodyBytes=%d consumerLimit=%d%n",
                expectedRecipients, expectedEvents, PRODUCER_BATCH_SIZE, maxBodyBytes, CONSUMER_MAX_BODY_BYTES);
    }

    @Test
    void emptyRecipientTransitionStillProducesExactlyOneEvent() throws Exception {
        // Disable every current company manager: the submitted transition then has no eligible
        // recipient, which must still record one canonical event (the pre-batching contract).
        owner.update("""
                UPDATE membership SET active = false WHERE company_id = ? AND id IN
                    (SELECT membership_id FROM membership_role WHERE company_id = ? AND role = 'DEPARTMENT_MANAGER')
                """, id("a"), id("a"));
        var requestId = createAndSubmitRequest();

        List<StoredEvent> events = submittedEvents(requestId);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().envelope().path("recipients").size()).isZero();
        assertThat(events.getFirst().body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(PRODUCER_ENVELOPE_BYTES);
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_recipient r JOIN outbox_event e "
                + "ON e.id = r.event_id WHERE e.request_id = ?", Integer.class, requestId)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_dispatch d JOIN outbox_event e "
                + "ON e.id = d.event_id WHERE e.request_id = ?", Integer.class, requestId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM request_history WHERE request_id = ? "
                + "AND action = 'request.submitted'", Integer.class, requestId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE entity_id = ? "
                + "AND action = 'request.submitted'", Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void exactFortyPlusOneBoundaryProducesTwoCanonicalEvents() throws Exception {
        seedExtraManagers(40);
        var requestId = createAndSubmitRequest();

        List<StoredEvent> events = submittedEvents(requestId);
        assertThat(events).hasSize(2);
        assertThat(events).extracting(event -> event.envelope().path("recipients").size())
                .containsExactlyInAnyOrder(40, 1);
        assertThat(events).allSatisfy(event ->
                assertThat(event.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(PRODUCER_ENVELOPE_BYTES));
        assertRecipientPartition(requestId, 41);
        assertThat(owner.queryForObject("SELECT count(*) FROM request_history WHERE request_id = ? "
                + "AND action = 'request.submitted'", Integer.class, requestId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE entity_id = ? "
                + "AND action = 'request.submitted'", Integer.class, requestId)).isEqualTo(1);
    }

    private UUID createAndSubmitRequest() throws Exception {
        var created = send("POST", "", content("50.00"), UUID.randomUUID());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var requestId = UUID.fromString(node(created).path("id").asText());
        var submitted = send("POST", "/" + requestId + "/submit", Map.of("version", 0), UUID.randomUUID());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
        return requestId;
    }

    private void seedExtraManagers(int count) {
        for (int i = 0; i < count; i++) {
            var identity = UUID.randomUUID();
            var membership = UUID.randomUUID();
            owner.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, ?, ?)",
                    identity, TOKENS.issuer(), "fanout-manager-" + i + "-" + UUID.randomUUID());
            owner.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                    + "VALUES (?, ?, ?, ?, true)", membership, id("a"), identity, id("a:engineering"));
            owner.update("INSERT INTO membership_role VALUES (?, ?, 'DEPARTMENT_MANAGER')", id("a"), membership);
            owner.update("INSERT INTO manager_department VALUES (?, ?, ?)", id("a"), membership, id("a:engineering"));
        }
    }

    private List<StoredEvent> submittedEvents(UUID requestId) {
        List<StoredEvent> events = new ArrayList<>();
        owner.query("""
                SELECT id, envelope::text AS body FROM outbox_event
                WHERE company_id = ? AND request_id = ? AND event_type = 'request.submitted'
                ORDER BY id
                """, rows -> {
            events.add(new StoredEvent(rows.getObject("id", UUID.class), rows.getString("body"),
                    JSON.readTree(rows.getString("body"))));
        }, id("a"), requestId);
        return events;
    }

    private void assertSameBusinessTransition(List<StoredEvent> events, UUID correlationId) {
        Set<String> payloads = new HashSet<>();
        for (StoredEvent event : events) {
            JsonNode envelope = event.envelope();
            assertThat(envelope.path("eventId").asText()).isEqualTo(event.id().toString());
            assertThat(envelope.path("companyId").asText()).isEqualTo(id("a").toString());
            assertThat(envelope.path("type").asText()).isEqualTo("request.submitted");
            assertThat(envelope.path("correlationId").asText()).isEqualTo(correlationId.toString());
            assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
            payloads.add(envelope.path("occurredAt").asText() + "|" + envelope.path("payload"));
        }
        assertThat(payloads).as("all batches share occurredAt/type/transition").hasSize(1);
    }

    private void assertRecipientPartition(UUID requestId, long expectedRecipients) {
        var rows = owner.queryForList("""
                SELECT r.event_id, r.identity_id, r.membership_id FROM outbox_recipient r
                JOIN outbox_event e ON e.id = r.event_id
                WHERE e.request_id = ? AND e.event_type = 'request.submitted'
                """, requestId);
        assertThat(rows).hasSize((int) expectedRecipients);
        assertThat(rows.stream().map(row -> row.get("identity_id")).distinct().count()).isEqualTo(expectedRecipients);
        assertThat(rows.stream().map(row -> row.get("membership_id")).distinct().count()).isEqualTo(expectedRecipients);

        Map<Object, Integer> perEvent = new LinkedHashMap<>();
        for (var row : rows) perEvent.merge(row.get("event_id"), 1, Integer::sum);
        assertThat(perEvent.values()).allSatisfy(count ->
                assertThat(count).isLessThanOrEqualTo(PRODUCER_BATCH_SIZE));

        // The envelope recipients must match the independent recipient table per event, exactly as
        // the consumer's canonical comparison requires.
        for (StoredEvent event : submittedEvents(requestId)) {
            var envelope = event.envelope();
            Set<String> envelopeKeys = new HashSet<>();
            for (JsonNode recipient : envelope.path("recipients")) {
                envelopeKeys.add(recipient.path("identityId").asText() + "|" + recipient.path("membershipId").asText());
            }
            Set<String> tableKeys = new HashSet<>();
            for (var row : rows) {
                if (row.get("event_id").equals(event.id())) {
                    tableKeys.add(row.get("identity_id") + "|" + row.get("membership_id"));
                }
            }
            assertThat(envelopeKeys).as("recipient snapshot for %s", event.id()).isEqualTo(tableKeys);
        }
    }

    private int materializeOnce(List<StoredEvent> events) {
        int processed = 0;
        for (StoredEvent event : events) {
            int inserted = worker.update("INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?) "
                    + "ON CONFLICT (company_id, event_id) DO NOTHING", id("a"), event.id());
            if (inserted == 0) continue;
            processed++;
            for (JsonNode recipient : event.envelope().path("recipients")) {
                var identity = UUID.fromString(recipient.path("identityId").asText());
                var membership = UUID.fromString(recipient.path("membershipId").asText());
                UUID inAppDelivery = UUID.randomUUID();
                worker.update("INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, "
                                + "channel, outcome) VALUES (?, ?, ?, ?, ?, 'IN_APP', 'MATERIALIZED')",
                        inAppDelivery, id("a"), event.id(), identity, membership);
                worker.update("INSERT INTO in_app_notification (id, delivery_id, company_id, event_id, identity_id, "
                                + "membership_id) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), inAppDelivery, id("a"), event.id(), identity, membership);
                UUID emailDelivery = UUID.randomUUID();
                worker.update("INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, "
                                + "channel, outcome) VALUES (?, ?, ?, ?, ?, 'EMAIL', 'MATERIALIZED')",
                        emailDelivery, id("a"), event.id(), identity, membership);
                worker.update("INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), emailDelivery, id("a"), event.id(), identity, membership);
            }
        }
        return processed;
    }

    private Map<String, Object> content(String amount) {
        return Map.of("title", "Fan-out fixture", "justification", "Bounded fan-out fixture", "amount", amount);
    }

    private HttpResponse<String> send(String method, String suffix, Object body, UUID key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/requests" + suffix))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKENS.token(id("a:employee:keycloak").toString()));
        if (key != null) request.header("Idempotency-Key", key.toString());
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode node(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private record StoredEvent(UUID id, String body, JsonNode envelope) {}
}

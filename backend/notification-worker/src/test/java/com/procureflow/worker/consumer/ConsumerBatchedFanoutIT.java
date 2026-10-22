package com.procureflow.worker.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.config.ConsumerProperties;
import com.procureflow.worker.config.RabbitTopology;
import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.RelayServiceFixture;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real PostgreSQL 18.6 / RabbitMQ 4.3.6 proof for the batched producer contract. The test seeds
 * multiple canonical events for one business transition, publishes their actual envelopes through
 * RabbitTemplate and lets the real {@link ConsumerMessageProcessor}/{@link ConsumerStore} path
 * validate, materialize and acknowledge them. It is not a reimplementation of the inbox SQL used by
 * the app-side bounded test.
 *
 * <p>The context deliberately uses the production 65,536-byte consumer bound; the shared worker IT
 * base lowers it to 4,096 bytes for its small envelopes. The negative control publishes one
 * unbatched 551-recipient envelope and observes the real MALFORMED quarantine, while the bounded
 * tests publish the same recipients as 40/1 batches and observe no diagnostic rows.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConsumerBatchedFanoutIT {
    private static final int PRODUCER_BATCH_SIZE = 40;
    private static final int LARGE_FANOUT = 551;
    private static final int BOUNDARY_FANOUT = 41;
    private static final Duration AWAIT = Duration.ofSeconds(30);

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private ConsumerStore store;
    @Autowired
    private ConsumerEnvelopeParser parser;
    @Autowired
    private ConsumerProperties properties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RelayServiceFixture::jdbcUrl);
        registry.add("spring.datasource.username", () -> RelayServiceFixture.WORKER_USER);
        registry.add("spring.datasource.password", RelayServiceFixture::workerPassword);
        registry.add("spring.rabbitmq.host", RelayServiceFixture::rabbitHost);
        registry.add("spring.rabbitmq.port", RelayServiceFixture::rabbitPort);
        registry.add("spring.rabbitmq.username", () -> RelayServiceFixture.RABBIT_USER);
        registry.add("spring.rabbitmq.password", RelayServiceFixture::rabbitPassword);
        registry.add("worker.relay.scheduled", () -> "false");
        registry.add("worker.smtp.enabled", () -> "false");
        registry.add("worker.consumer.enabled", () -> "true");
        registry.add("worker.consumer.prefetch-count", () -> "4");
        registry.add("worker.consumer.concurrent-consumers", () -> "2");
        registry.add("worker.consumer.max-concurrent-consumers", () -> "2");
        registry.add("worker.consumer.max-attempts", () -> "5");
        registry.add("worker.consumer.retry-backoff", () -> "PT0.4S");
        registry.add("worker.consumer.retry-backoff-multiplier", () -> "2.0");
        registry.add("worker.consumer.retry-backoff-max", () -> "PT1S");
        // The production default; the batched envelopes are about 6.3 KiB and must not be malformed.
        registry.add("worker.consumer.max-body-bytes", () -> "65536");
        registry.add("worker.consumer.shutdown-timeout", () -> "PT5S");
    }

    @BeforeEach
    void reset() {
        ConsumerServiceFixture.reset();
    }

    @Test
    void batchedLargeFanOutMaterializesExactlyOnceWithNoMalformedQuarantine() {
        var fixture = seedBatches(LARGE_FANOUT);
        assertThat(fixture.batches).hasSize((LARGE_FANOUT + PRODUCER_BATCH_SIZE - 1) / PRODUCER_BATCH_SIZE);
        assertThat(fixture.batches.stream().mapToInt(SeededBatch::recipientCount).sum())
                .isEqualTo(LARGE_FANOUT);
        assertThat(fixture.batches).allSatisfy(batch ->
                assertThat(batch.recipientCount()).isLessThanOrEqualTo(PRODUCER_BATCH_SIZE));
        publishAll(fixture);
        awaitAllInboxes(fixture);
        await("queue drained after batched fan-out", () -> ConsumerServiceFixture.queueDepth().drained());

        assertNoDiagnostics();
        assertBatchesMatchCanonicalSnapshot(fixture);
        assertOneInAppAndEmailJobPerIdentity(fixture, LARGE_FANOUT);
        assertThat(ConsumerServiceFixture.total("notification_diagnostic"))
                .as("no MALFORMED or other diagnostic row").isZero();

        // Duplicate delivery: stop the listener, enqueue the same 14 messages, prove the queue
        // actually holds them, then restart the real consumer. The inbox gate must acknowledge them
        // without adding any delivery/in-app/email row.
        var container = listenerRegistry.getListenerContainer(NotificationQueueListener.LISTENER_ID);
        assertThat(container).isNotNull();
        container.stop();
        publishAll(fixture);
        await("duplicate messages queued", () -> ConsumerServiceFixture.queueDepth().ready() >= fixture.batches.size());
        container.start();
        await("duplicate deliveries acknowledged", () -> ConsumerServiceFixture.queueDepth().drained());

        assertNoDiagnostics();
        assertBatchesMatchCanonicalSnapshot(fixture);
        assertOneInAppAndEmailJobPerIdentity(fixture, LARGE_FANOUT);
        assertThat(ConsumerServiceFixture.total("notification_diagnostic"))
                .as("duplicate delivery must not create quarantine rows").isZero();
    }

    @Test
    void exactFortyPlusOneBoundaryIsMaterializedAsTwoRealConsumerBatches() {
        var fixture = seedBatches(BOUNDARY_FANOUT);
        assertThat(fixture.batches).extracting(SeededBatch::recipientCount)
                .containsExactlyInAnyOrder(40, 1);

        publishAll(fixture);
        awaitAllInboxes(fixture);
        await("queue drained after boundary fan-out", () -> ConsumerServiceFixture.queueDepth().drained());

        assertNoDiagnostics();
        assertBatchesMatchCanonicalSnapshot(fixture);
        assertOneInAppAndEmailJobPerIdentity(fixture, BOUNDARY_FANOUT);
    }

    /**
     * Negative control at the same production bound: one unbatched envelope with the same 551
     * recipients is larger than 65,536 bytes, so the real processor must quarantine it as
     * MALFORMED_MESSAGE before canonical comparison and write no inbox/delivery rows. This is the
     * red condition the bounded producer prevents.
     */
    @Test
    void unbatchedLargeEnvelopeIsQuarantinedAsMalformedByTheRealConsumer() {
        var actor = ConsumerServiceFixture.createActor("unbatched");
        var memberships = new ArrayList<ConsumerServiceFixture.Membership>();
        for (int index = 0; index < LARGE_FANOUT; index++) {
            memberships.add(ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true));
        }
        var recipients = memberships.stream().map(ConsumerServiceFixture.RecipientSeed::both).toList();
        var seeded = ConsumerServiceFixture.seedEvent(actor, "request.submitted", recipients);
        assertThat(seeded.envelope().getBytes(StandardCharsets.UTF_8).length)
                .as("the unbatched control must exceed the real consumer bound")
                .isGreaterThan(properties.maxBodyBytes());

        publish(seeded.event(), seeded.envelope(), seeded.correlation());
        await("malformed quarantine", () -> ConsumerServiceFixture.total("notification_diagnostic") == 1);

        var diagnostics = ConsumerServiceFixture.diagnosticsWithoutScope();
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.errorCode()).isEqualTo("malformed_message");
            assertThat(diagnostic.companyId()).isNull();
            assertThat(diagnostic.eventId()).isNull();
        });
        assertThat(ConsumerServiceFixture.count("notification_inbox", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.count("notification_delivery", seeded.company(), seeded.event())).isZero();
        assertThat(ConsumerServiceFixture.countByCompany("in_app_notification", seeded.company())).isZero();
        assertThat(ConsumerServiceFixture.countByCompany("email_job", seeded.company())).isZero();
    }

    private SeededBatches seedBatches(int total) {
        var actor = ConsumerServiceFixture.createActor("batched");
        var memberships = new ArrayList<ConsumerServiceFixture.Membership>();
        for (int index = 0; index < total; index++) {
            memberships.add(ConsumerServiceFixture.createMembership(actor.company(), actor.department(), true));
        }
        List<ConsumerServiceFixture.RecipientSeed> all = memberships.stream()
                .map(ConsumerServiceFixture.RecipientSeed::both).toList();

        var batches = new ArrayList<SeededBatch>();
        int offset = 0;
        ConsumerServiceFixture.SeededEvent first = null;
        while (offset < all.size()) {
            int end = Math.min(offset + PRODUCER_BATCH_SIZE, all.size());
            var batchRecipients = all.subList(offset, end);
            UUID eventId;
            String envelope;
            if (first == null) {
                first = ConsumerServiceFixture.seedEvent(actor, "request.submitted", batchRecipients);
                eventId = first.event();
                envelope = first.envelope();
            } else {
                eventId = UUID.randomUUID();
                envelope = ConsumerServiceFixture.envelope(eventId, "request.submitted", actor.company(),
                        first.request(), first.correlation(), first.occurredAt(), "DRAFT",
                        "AWAITING_DEPARTMENT_APPROVAL", 1, first.actorIdentity(), batchRecipients);
                RelayServiceFixture.owner().update("""
                        INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version,
                        correlation_id, occurred_at, envelope)
                        VALUES (?, ?, ?, 'request.submitted', 1, ?, ?, CAST(? AS jsonb))
                        """, eventId, actor.company(), first.request(), first.correlation(),
                        Timestamp.from(first.occurredAt()), envelope);
                for (var recipient : batchRecipients) {
                    RelayServiceFixture.owner().update("INSERT INTO outbox_recipient "
                                    + "(company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                            actor.company(), eventId, recipient.identityId(), recipient.membershipId());
                }
            }
            batches.add(new SeededBatch(eventId, envelope, batchRecipients.size()));
            offset = end;
        }
        return new SeededBatches(actor.company(), first.correlation(), batches);
    }

    private void publishAll(SeededBatches fixture) {
        for (var batch : fixture.batches) {
            publish(batch.eventId(), batch.envelope(), fixture.correlation);
        }
    }

    private void publish(UUID eventId, String envelope, UUID correlationId) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setMessageId(eventId.toString());
        messageProperties.setCorrelationId(correlationId.toString());
        messageProperties.setType("request.submitted");
        messageProperties.setHeader("schemaVersion", 1);
        rabbitTemplate.send(RabbitTopology.EXCHANGE, "request.submitted",
                new Message(envelope.getBytes(StandardCharsets.UTF_8), messageProperties),
                new CorrelationData(UUID.randomUUID().toString()));
    }

    private void awaitAllInboxes(SeededBatches fixture) {
        for (var batch : fixture.batches) {
            await("inbox materialization for event " + batch.eventId(),
                    () -> ConsumerServiceFixture.inbox(fixture.company, batch.eventId()).isPresent());
        }
    }

    private void assertNoDiagnostics() {
        assertThat(ConsumerServiceFixture.total("notification_diagnostic"))
                .as("batched fan-out must never be classified as MALFORMED or retried into a diagnostic")
                .isZero();
    }

    private void assertBatchesMatchCanonicalSnapshot(SeededBatches fixture) {
        for (var batch : fixture.batches) {
            var bytes = batch.envelope().getBytes(StandardCharsets.UTF_8);
            assertThat(bytes.length).as("body of %s", batch.eventId())
                    .isLessThanOrEqualTo(properties.maxBodyBytes());
            var canonical = store.findCanonical(fixture.company, batch.eventId()).orElseThrow();
            var received = parser.parse(bytes);
            var stored = parser.parse(canonical.envelopeJson().getBytes(StandardCharsets.UTF_8));
            assertThat(received.valid()).isTrue();
            assertThat(stored.valid()).isTrue();
            assertThat(ConsumerEnvelopeRules.matchesCanonical(received.envelope(), stored.envelope()))
                    .as("canonical envelope comparison for %s", batch.eventId()).isTrue();
            assertThat(ConsumerEnvelopeRules.recipientTableMatches(received.envelope(), canonical.recipients()))
                    .as("canonical recipient-table comparison for %s", batch.eventId()).isTrue();
            assertThat(ConsumerServiceFixture.inbox(fixture.company, batch.eventId())).isPresent();
            assertThat(ConsumerServiceFixture.count("notification_inbox", fixture.company, batch.eventId()))
                    .as("exactly one inbox marker for %s", batch.eventId()).isEqualTo(1);
        }
    }

    private void assertOneInAppAndEmailJobPerIdentity(SeededBatches fixture, int expectedRecipients) {
        assertThat(ConsumerServiceFixture.countByCompany("notification_delivery", fixture.company))
                .isEqualTo(expectedRecipients * 2L);
        assertThat(ConsumerServiceFixture.countByCompany("in_app_notification", fixture.company))
                .isEqualTo(expectedRecipients);
        assertThat(ConsumerServiceFixture.countByCompany("email_job", fixture.company))
                .isEqualTo(expectedRecipients);
        var duplicates = RelayServiceFixture.worker().queryForList("""
                SELECT identity_id FROM notification_delivery WHERE company_id = ?
                GROUP BY identity_id, channel HAVING count(*) <> 1
                """, UUID.class, fixture.company);
        assertThat(duplicates).isEmpty();
        var inAppDuplicates = RelayServiceFixture.worker().queryForList("""
                SELECT identity_id FROM in_app_notification WHERE company_id = ?
                GROUP BY identity_id HAVING count(*) <> 1
                """, UUID.class, fixture.company);
        assertThat(inAppDuplicates).isEmpty();
        var emailDuplicates = RelayServiceFixture.worker().queryForList("""
                SELECT identity_id FROM email_job WHERE company_id = ?
                GROUP BY identity_id HAVING count(*) <> 1
                """, UUID.class, fixture.company);
        assertThat(emailDuplicates).isEmpty();
    }

    private static void await(String description, BooleanSupplier condition) {
        ConsumerServiceFixture.await(AWAIT, description, condition);
    }

    private record SeededBatches(UUID company, UUID correlation, List<SeededBatch> batches) {
    }

    private record SeededBatch(UUID eventId, String envelope, int recipientCount) {
    }
}

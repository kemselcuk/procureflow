package com.procureflow.worker.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procureflow.worker.config.RabbitTopology;
import com.procureflow.worker.support.CapturingInstrumentation;
import com.procureflow.worker.support.RelayServiceFixture;
import com.procureflow.worker.support.WorkerIntegrationTestBase;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Broker behavior against real RabbitMQ 4.3.6: durable topology, persistent schema-v1 publication,
 * mandatory returns, real nacks, bounded confirms, outage recovery and restart persistence.
 */
class RelayBrokerIT extends WorkerIntegrationTestBase {

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void publishesCanonicalEnvelopeWithPersistentMessageMetadata() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        assertThat(RelayServiceFixture.dispatch(seeded.event()).state())
                .as("the producer trigger initializes dispatch before any relay run").isEqualTo("PENDING");

        relay.runOnce();

        var row = RelayServiceFixture.dispatch(seeded.event());
        assertThat(row.state()).isEqualTo("PUBLISHED");
        assertThat(row.publishedAt()).isNotNull();
        assertThat(row.errorCode()).isNull();
        assertThat(row.leaseToken()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.attempts()).isEqualTo(1);

        try (var connection = RelayServiceFixture.rabbitConnection();
             var channel = connection.createChannel()) {
            GetResponse response = channel.basicGet(RabbitTopology.QUEUE, false);
            assertThat(response).as("the durable queue must hold the published event").isNotNull();
            var properties = response.getProps();
            assertThat(properties.getDeliveryMode()).as("persistent delivery mode").isEqualTo(2);
            assertThat(properties.getContentType()).isEqualTo("application/json");
            assertThat(properties.getMessageId()).isEqualTo(seeded.event().toString());
            assertThat(properties.getCorrelationId()).isEqualTo(seeded.correlation().toString());
            assertThat(properties.getType()).isEqualTo(seeded.type());
            assertThat(properties.getHeaders().get("schemaVersion")).isEqualTo(1);
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(seeded.envelope());
            channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
        }
    }

    @Test
    void durableTopicTopologyIsClassicQueueBoundToRequestEvents() {
        var exchange = RelayServiceFixture.management("GET", "/exchanges/%2F/" + RabbitTopology.EXCHANGE);
        assertThat(exchange.get("type").asString()).isEqualTo("topic");
        assertThat(exchange.get("durable").asBoolean()).isTrue();
        assertThat(exchange.get("auto_delete").asBoolean()).isFalse();

        var queue = RelayServiceFixture.management("GET", "/queues/%2F/" + RabbitTopology.QUEUE);
        assertThat(queue.get("durable").asBoolean()).isTrue();
        assertThat(queue.get("auto_delete").asBoolean()).isFalse();
        assertThat(queue.get("exclusive").asBoolean()).isFalse();
        assertThat(queue.get("type").asString()).isEqualTo("classic");
        assertThat(queue.get("consumer_details").size()).isZero();

        var bindings = RelayServiceFixture.management("GET",
                "/bindings/%2F/e/" + RabbitTopology.EXCHANGE + "/q/" + RabbitTopology.QUEUE);
        assertThat(bindings.isArray()).isTrue();
        boolean bound = false;
        for (var binding : bindings) {
            bound |= RabbitTopology.BINDING_PATTERN.equals(binding.get("routing_key").asString());
        }
        assertThat(bound).as("queue must be bound with the request.* pattern").isTrue();
    }

    @Test
    void mandatoryUnroutableReturnDefeatsTheBrokerAck() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        warmConnection();
        var captured = new CapturingInstrumentation();
        try (var control = RelayServiceFixture.rabbitConnection();
             var channel = control.createChannel()) {
            channel.queueUnbind(RabbitTopology.QUEUE, RabbitTopology.EXCHANGE, RabbitTopology.BINDING_PATTERN);
            try {
                relayWith(captured).runOnce();

                var row = RelayServiceFixture.dispatch(seeded.event());
                assertThat(row.state()).as("an unroutable message must never be marked published")
                        .isEqualTo("PENDING");
                assertThat(row.errorCode()).isEqualTo("broker_returned");
                assertThat(row.attempts()).isEqualTo(1);
                assertThat(row.publishedAt()).isNull();

                var correlation = captured.correlationFor(seeded.event());
                assertThat(correlation).isNotNull();
                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                assertThat(confirm.isAck())
                        .as("RabbitMQ acknowledges an unroutable mandatory message; the return must defeat it")
                        .isTrue();
                assertThat(correlation.getReturned()).as("the mandatory return must be observed").isNotNull();
                assertThat(correlation.getReturned().getReplyCode()).isEqualTo(312);
            } finally {
                channel.queueBind(RabbitTopology.QUEUE, RabbitTopology.EXCHANGE, RabbitTopology.BINDING_PATTERN);
            }
        }
        makeDue(seeded.event());
        relay.runOnce();
        assertThat(RelayServiceFixture.dispatch(seeded.event()).state()).isEqualTo("PUBLISHED");
        assertThat(RelayServiceFixture.messageCount()).isEqualTo(1);
    }

    @Test
    void rejectPublishOverflowProducesARealBrokerNack() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        warmConnection();
        var captured = new CapturingInstrumentation();
        String rejectQueue = "procureflow.test.reject." + UUID.randomUUID();
        try (var control = RelayServiceFixture.rabbitConnection();
             var channel = control.createChannel()) {
            channel.queueDeclare(rejectQueue, false, true, true,
                    Map.of("x-max-length", 0, "x-overflow", "reject-publish"));
            channel.queueBind(rejectQueue, RabbitTopology.EXCHANGE, seeded.type());
            try {
                relayWith(captured).runOnce();
                var row = RelayServiceFixture.dispatch(seeded.event());
                assertThat(row.state()).isEqualTo("PENDING");
                assertThat(row.errorCode()).isEqualTo("broker_nack");
                assertThat(row.attempts()).isEqualTo(1);
                var correlation = captured.correlationFor(seeded.event());
                assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck()).isFalse();
            } finally {
                channel.queueDelete(rejectQueue);
            }
        }
        makeDue(seeded.event());
        relay.runOnce();
        assertThat(RelayServiceFixture.dispatch(seeded.event()).state()).isEqualTo("PUBLISHED");
    }

    @Test
    void confirmTimeoutLeavesWorkRetryableAndALateAckCannotPublish() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        warmConnection();
        var captured = new CapturingInstrumentation();
        var instrumented = relayWith(captured);
        boolean unpaused = false;
        var timeoutMillis = properties.confirmTimeout().toMillis();
        RelayServiceFixture.pauseBroker();
        try {
            long startedAt = System.nanoTime();
            instrumented.runOnce();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertThat(elapsedMillis).as("the confirm wait must be bounded by the configured timeout")
                    .isGreaterThanOrEqualTo(timeoutMillis);
            assertThat(elapsedMillis).as("the confirm wait must not stall the relay").isLessThan(timeoutMillis + 15_000);

            var row = RelayServiceFixture.dispatch(seeded.event());
            assertThat(row.state()).isEqualTo("PENDING");
            assertThat(row.errorCode()).isEqualTo("publish_timeout");
            assertThat(row.attempts()).isEqualTo(1);

            var correlation = captured.correlationFor(seeded.event());
            assertThat(correlation).isNotNull();
            assertThat(correlation.getFuture().isDone())
                    .as("no confirmation can be processed while the broker is paused").isFalse();

            RelayServiceFixture.unpauseBroker();
            unpaused = true;
            var lateConfirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            assertThat(lateConfirm.isAck()).as("the late confirmation arrives after the timeout").isTrue();
            assertThat(correlation.getReturned()).isNull();
        } finally {
            if (!unpaused) {
                RelayServiceFixture.unpauseBroker();
            }
        }
        var afterLateAck = RelayServiceFixture.dispatch(seeded.event());
        assertThat(afterLateAck.state()).as("a late ack must not complete already-retried work").isEqualTo("PENDING");
        assertThat(afterLateAck.errorCode()).isEqualTo("publish_timeout");
        assertThat(afterLateAck.attempts()).isEqualTo(1);
        assertThat(awaitMessageCount(1)).as("the paused broker processed the buffered publish after unpause").isTrue();
    }

    @Test
    void confirmBeforeSaveCrashRecoversByReclaimAndDuplicatesOnlyTheStableEventId() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        var crash = new CapturingInstrumentation();
        crash.failBeforeSave("simulated relay crash after confirm");
        assertThatThrownBy(() -> relayWith(crash).runOnce())
                .hasMessageContaining("simulated relay crash after confirm");

        var crashed = RelayServiceFixture.dispatch(seeded.event());
        assertThat(crashed.state()).isEqualTo("LEASED");
        assertThat(crashed.attempts()).isEqualTo(1);
        assertThat(crashed.publishedAt()).isNull();

        RelayServiceFixture.expireLease(seeded.event());
        relay.runOnce();
        var recovered = RelayServiceFixture.dispatch(seeded.event());
        assertThat(recovered.state()).isEqualTo("PUBLISHED");
        assertThat(recovered.attempts()).isEqualTo(2);

        try (var connection = RelayServiceFixture.rabbitConnection();
             var channel = connection.createChannel()) {
            assertThat(channel.messageCount(RabbitTopology.QUEUE)).isEqualTo(2);
            GetResponse first = channel.basicGet(RabbitTopology.QUEUE, false);
            GetResponse second = channel.basicGet(RabbitTopology.QUEUE, false);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.getProps().getMessageId()).isEqualTo(seeded.event().toString());
            assertThat(second.getProps().getMessageId()).isEqualTo(seeded.event().toString());
            assertThat(first.getBody()).isEqualTo(second.getBody());
            assertThat(new String(first.getBody(), StandardCharsets.UTF_8)).isEqualTo(seeded.envelope());
            channel.basicAck(first.getEnvelope().getDeliveryTag(), false);
            channel.basicAck(second.getEnvelope().getDeliveryTag(), false);
        }
    }

    @Test
    void brokerOutageBacksOffThenRecoversWithinTheFixtureBound() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        RelayServiceFixture.stopBroker();
        try {
            relay.runOnce();
            var failed = RelayServiceFixture.dispatch(seeded.event());
            assertThat(failed.state()).isEqualTo("PENDING");
            assertThat(failed.errorCode()).isEqualTo("broker_unreachable");
            assertThat(failed.attempts()).isEqualTo(1);
            assertThat(failed.nextAttemptAt()).isAfter(Timestamp.from(Instant.now()));
            assertThat(dispatchStore.claimDue(properties, properties.batchSize()).events())
                    .as("backed-off work is not retried immediately").isEmpty();
        } finally {
            RelayServiceFixture.startBroker();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        boolean published = false;
        while (!published && System.nanoTime() < deadline) {
            relay.runOnce();
            published = "PUBLISHED".equals(RelayServiceFixture.dispatch(seeded.event()).state());
            if (!published) {
                Thread.sleep(100);
            }
        }
        assertThat(published).as("recovery must complete within the 60-second fixture bound").isTrue();
        assertThat(RelayServiceFixture.dispatch(seeded.event()).attempts())
                .as("recovery republishes the backed-off event")
                .isGreaterThanOrEqualTo(2);
        assertThat(awaitMessageCount(1)).isTrue();
    }

    @Test
    void topologyRecoversAfterTransientConnectionLoss() throws Exception {
        warmConnection();
        RelayServiceFixture.management("DELETE", "/queues/%2F/" + RabbitTopology.QUEUE);
        var queueMissing = assertThatThrownBy(
                () -> RelayServiceFixture.management("GET", "/queues/%2F/" + RabbitTopology.QUEUE));
        queueMissing.isInstanceOf(IllegalStateException.class);

        // Client-side connection reset: the next channel request establishes a new connection, which
        // must trigger the admin listener to redeclare the durable topology.
        ((CachingConnectionFactory) connectionFactory).resetConnection();

        boolean redeclared = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            while (!redeclared && System.nanoTime() < deadline) {
                try {
                    rabbitTemplate.execute(channel -> {
                        channel.queueDeclarePassive(RabbitTopology.QUEUE);
                        return null;
                    });
                    redeclared = true;
                } catch (RuntimeException e) {
                    Thread.sleep(200);
                }
            }
            assertThat(redeclared).as("the admin listener must redeclare the queue after reconnecting").isTrue();
            var queue = RelayServiceFixture.management("GET", "/queues/%2F/" + RabbitTopology.QUEUE);
            assertThat(queue.get("durable").asBoolean()).isTrue();
            assertThat(queue.get("type").asString()).isEqualTo("classic");
            var bindings = RelayServiceFixture.management("GET",
                    "/bindings/%2F/e/" + RabbitTopology.EXCHANGE + "/q/" + RabbitTopology.QUEUE);
            boolean bound = false;
            for (var binding : bindings) {
                bound |= RabbitTopology.BINDING_PATTERN.equals(binding.get("routing_key").asString());
            }
            assertThat(bound).as("the binding must be redeclared together with the queue").isTrue();
        } finally {
            if (!redeclared) {
                try (var connection = RelayServiceFixture.rabbitConnection();
                     var channel = connection.createChannel()) {
                    channel.queueDeclare(RabbitTopology.QUEUE, true, false, false,
                            Map.of("x-queue-type", "classic"));
                    channel.queueBind(RabbitTopology.QUEUE, RabbitTopology.EXCHANGE, RabbitTopology.BINDING_PATTERN);
                }
            }
        }
    }

    @Test
    void persistedMessageSurvivesABrokerRestart() throws Exception {
        var seeded = RelayServiceFixture.seedEvent();
        relay.runOnce();
        assertThat(RelayServiceFixture.dispatch(seeded.event()).state()).isEqualTo("PUBLISHED");
        assertThat(awaitMessageCount(1)).isTrue();

        RelayServiceFixture.restartBroker();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long count = -1;
        while (System.nanoTime() < deadline) {
            try {
                count = RelayServiceFixture.messageCount();
                if (count == 1) {
                    break;
                }
            } catch (Exception ignored) {
                // The broker may still be recovering; the pass condition is the observed count.
            }
            Thread.sleep(200);
        }
        assertThat(count).as("a confirmed persistent message must survive the broker restart").isEqualTo(1);

        try (var connection = RelayServiceFixture.rabbitConnection();
             var channel = connection.createChannel()) {
            GetResponse response = channel.basicGet(RabbitTopology.QUEUE, false);
            assertThat(response).isNotNull();
            assertThat(response.getProps().getMessageId()).isEqualTo(seeded.event().toString());
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(seeded.envelope());
            channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
        }
    }

    @Test
    void slowFirstPublishDoesNotExpireLaterClaimsOrWasteTheirAttempts() throws Exception {
        var first = RelayServiceFixture.seedEvent();
        var second = RelayServiceFixture.seedEvent();
        var third = RelayServiceFixture.seedEvent();
        warmConnection();
        var captured = new CapturingInstrumentation();
        captured.delaySendRequests(1, Duration.ofMillis(2_500));

        relayWithShortLease(captured).runOnce();

        for (UUID later : List.of(second.event(), third.event())) {
            var row = RelayServiceFixture.dispatch(later);
            assertThat(row.state())
                    .as("a later event must not lose its claim while waiting behind earlier I/O")
                    .isEqualTo("PUBLISHED");
            assertThat(row.attempts())
                    .as("a later event must not consume attempts while waiting behind earlier I/O")
                    .isEqualTo(1);
        }
        var republished = RelayServiceFixture.dispatch(first.event());
        assertThat(republished.state()).as("the reclaimed first event is republished").isEqualTo("PUBLISHED");
        assertThat(republished.attempts())
                .as("only the event whose own I/O overran its lease is retried").isEqualTo(2);
        assertThat(awaitMessageCount(4)).as("the reclaimed event duplicates only its stable id").isTrue();
        try (var connection = RelayServiceFixture.rabbitConnection();
             var channel = connection.createChannel()) {
            List<UUID> messageIds = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                GetResponse response = channel.basicGet(RabbitTopology.QUEUE, false);
                assertThat(response).isNotNull();
                messageIds.add(UUID.fromString(response.getProps().getMessageId()));
                channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
            }
            assertThat(messageIds).containsExactlyInAnyOrder(
                    first.event(), first.event(), second.event(), third.event());
        }
    }

    @Test
    void slowFirstPublishDoesNotExpireLaterFailureSavesAfterARealBrokerReturn() throws Exception {
        var first = RelayServiceFixture.seedEvent();
        var second = RelayServiceFixture.seedEvent();
        var third = RelayServiceFixture.seedEvent();
        Instant startedAt = Instant.now();
        warmConnection();
        var captured = new CapturingInstrumentation();
        captured.delaySendRequests(1, Duration.ofMillis(2_500));
        try (var control = RelayServiceFixture.rabbitConnection();
             var channel = control.createChannel()) {
            channel.queueUnbind(RabbitTopology.QUEUE, RabbitTopology.EXCHANGE, RabbitTopology.BINDING_PATTERN);
            try {
                relayWithShortLease(captured).runOnce();
            } finally {
                channel.queueBind(RabbitTopology.QUEUE, RabbitTopology.EXCHANGE, RabbitTopology.BINDING_PATTERN);
            }
        }
        for (UUID later : List.of(second.event(), third.event())) {
            var row = RelayServiceFixture.dispatch(later);
            assertThat(row.state())
                    .as("a real broker return must schedule a backed-off retry, not strand the claim")
                    .isEqualTo("PENDING");
            assertThat(row.errorCode()).isEqualTo("broker_returned");
            assertThat(row.attempts()).isEqualTo(1);
            assertThat(row.nextAttemptAt()).isAfter(Timestamp.from(startedAt));
        }
        var retried = RelayServiceFixture.dispatch(first.event());
        assertThat(retried.state()).as("the reclaimed first event is deferred with backoff").isEqualTo("PENDING");
        assertThat(retried.errorCode()).isEqualTo("broker_returned");
        assertThat(retried.attempts()).isEqualTo(2);
    }

    /** Production collaborators with a two-second lease so slow-I/O scheduling is observable. */
    private NotificationOutboxRelay relayWithShortLease(CapturingInstrumentation instrumentation) {
        RelayProperties shortLease = new RelayProperties(6, 40, Duration.ofSeconds(2), Duration.ofSeconds(2), 5,
                Duration.ofMillis(500), 2.0, Duration.ofSeconds(2), Duration.ofHours(1));
        OutboxPublisher publisher = new OutboxPublisher(rabbitTemplate, shortLease, instrumentation);
        return new NotificationOutboxRelay(dispatchStore, publisher, shortLease, instrumentation);
    }

    private void warmConnection() {
        rabbitTemplate.execute(channel -> {
            channel.queueDeclarePassive(RabbitTopology.QUEUE);
            return null;
        });
    }

    private static void makeDue(UUID eventId) {
        RelayServiceFixture.worker().update(
                "UPDATE outbox_dispatch SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
                eventId);
    }

    private static boolean awaitMessageCount(long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try {
                if (RelayServiceFixture.messageCount() == expected) {
                    return true;
                }
            } catch (Exception ignored) {
                // Retry until the broker recovers; pass condition is the observed count.
            }
            Thread.sleep(100);
        }
        return RelayServiceFixture.messageCount() == expected;
    }
}

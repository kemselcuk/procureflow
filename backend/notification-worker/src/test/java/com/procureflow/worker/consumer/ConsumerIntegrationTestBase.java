package com.procureflow.worker.consumer;

import com.procureflow.worker.config.ConsumerProperties;
import com.procureflow.worker.config.RabbitTopology;
import com.procureflow.worker.relay.NotificationOutboxRelay;
import com.procureflow.worker.support.ConsumerServiceFixture;
import com.procureflow.worker.support.RelayServiceFixture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boot context with the real PostgreSQL/RabbitMQ fixtures and the consumer listener enabled. The
 * scheduled relay is disabled so each test drives publication deterministically. The context is
 * closed after each test class: a cached consumer would otherwise compete with relay-only tests for
 * queue messages.
 */
@SpringBootTest
@Import(ConsumerTestInstrumentationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class ConsumerIntegrationTestBase {
    protected static final Duration AWAIT = Duration.ofSeconds(20);

    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    protected NotificationOutboxRelay relay;
    @Autowired
    protected ConsumerProperties properties;
    @Autowired
    protected ConsumerInstrumentation instrumentation;

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
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
        registry.add("worker.consumer.max-body-bytes", () -> "4096");
        registry.add("worker.consumer.shutdown-timeout", () -> "PT5S");
    }

    @BeforeEach
    void resetSharedState() {
        ConsumerServiceFixture.reset();
        ConsumerTestHooks.reset();
    }

    protected void publish(ConsumerServiceFixture.SeededEvent seeded) {
        publish(seeded.envelope(), seeded.event(), seeded.type(), seeded.correlation(), 1);
    }

    protected void publish(String envelopeJson, UUID messageId, String type, UUID correlationId,
                           Integer schemaVersionHeader) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        if (messageId != null) {
            properties.setMessageId(messageId.toString());
        }
        if (correlationId != null) {
            properties.setCorrelationId(correlationId.toString());
        }
        properties.setType(type);
        if (schemaVersionHeader != null) {
            properties.setHeader("schemaVersion", schemaVersionHeader);
        }
        rabbitTemplate.send(RabbitTopology.EXCHANGE, "request.test",
                new Message(envelopeJson.getBytes(StandardCharsets.UTF_8), properties),
                new CorrelationData(UUID.randomUUID().toString()));
    }

    protected void publishRaw(String body) {
        publish(body, UUID.randomUUID(), "request.test", UUID.randomUUID(), 1);
    }

    protected void failAfterNextMaterializationCommit() {
        ConsumerTestHooks.crashAfterNextCommit();
    }

    protected void stopListener() {
        Objects.requireNonNull(listenerRegistry.getListenerContainer(NotificationQueueListener.LISTENER_ID))
                .stop();
    }

    protected void startListener() {
        Objects.requireNonNull(listenerRegistry.getListenerContainer(NotificationQueueListener.LISTENER_ID))
                .start();
    }

    protected static void await(String description, java.util.function.BooleanSupplier condition) {
        ConsumerServiceFixture.await(AWAIT, description, condition);
    }
}

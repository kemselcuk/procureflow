package com.procureflow.worker.support;

import com.procureflow.worker.relay.DispatchStore;
import com.procureflow.worker.relay.NotificationOutboxRelay;
import com.procureflow.worker.relay.OutboxPublisher;
import com.procureflow.worker.relay.RelayInstrumentation;
import com.procureflow.worker.relay.RelayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boot context wired exactly like production (worker JDBC login, publisher confirms, mandatory
 * returns, durable topology), pointed at the real service fixtures. The scheduled poller is
 * disabled so each test drives {@code runOnce()} deterministically.
 */
@SpringBootTest
public abstract class WorkerIntegrationTestBase {
    @Autowired
    protected DispatchStore dispatchStore;
    @Autowired
    protected NotificationOutboxRelay relay;
    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected RelayProperties properties;

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
        registry.add("worker.relay.batch-size", () -> "10");
        registry.add("worker.relay.max-inflight", () -> "40");
        registry.add("worker.relay.lease-timeout", () -> "PT30S");
        registry.add("worker.relay.confirm-timeout", () -> "PT2S");
        registry.add("worker.relay.max-attempts", () -> "5");
        registry.add("worker.relay.retry-backoff", () -> "PT0.5S");
        registry.add("worker.relay.retry-backoff-multiplier", () -> "2.0");
        registry.add("worker.relay.retry-backoff-max", () -> "PT2S");
        registry.add("worker.relay.poll-interval", () -> "PT1H");
    }

    @BeforeEach
    void resetSharedState() {
        RelayServiceFixture.reset();
    }

    /** Production collaborators with a test-only failure-injection seam. */
    protected NotificationOutboxRelay relayWith(RelayInstrumentation instrumentation) {
        OutboxPublisher publisher = new OutboxPublisher(rabbitTemplate, properties, instrumentation);
        return new NotificationOutboxRelay(dispatchStore, publisher, properties, instrumentation);
    }
}

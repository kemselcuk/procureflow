package com.procureflow.worker.config;

import com.procureflow.worker.consumer.ConsumerInstrumentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ErrorHandler;

/**
 * Infrastructure for the notification consumer listener.
 *
 * <p>The container is deliberately manual-ack and singly owned by the consumer: an ACK is sent only
 * after committed normal effects or a committed quarantine record with terminal attempt evidence. A
 * crash, a lost connection or a container stop therefore leaves the delivery unacknowledged and the
 * broker requeues it; the inbox unique key then removes any duplicate effect. While the process is
 * alive, an uncommittable delivery is retained by the listener and retried with a capped,
 * interruptible backoff, so a longer database outage recovers without a worker/channel restart. The
 * prefetch count and the consumer concurrency are small, bounded values, because the local worker is
 * one process without a throughput or high-availability claim.
 *
 * <p>This class is feature-flagged by the {@code worker.consumer.enabled} property read on the
 * listener itself; the factory bean exists regardless but opens no connection until a listener uses
 * it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsumerProperties.class)
class ConsumerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ConsumerConfiguration.class);

    @Bean
    SimpleRabbitListenerContainerFactory consumerRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            ConsumerProperties properties) {
        var factory = new SimpleRabbitListenerContainerFactory();
        // Inherits the Boot-managed connection factory, message converter and observation settings.
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(properties.prefetchCount());
        factory.setConcurrentConsumers(properties.concurrentConsumers());
        factory.setMaxConcurrentConsumers(properties.maxConcurrentConsumers());
        // No automatic reject path may drop an uncommitted delivery: a missing queue is not fatal
        // (the topology declaration re-runs when the broker returns) and if an unexpected exception
        // ever reaches the reject path it requeues rather than discards. The error handler only
        // logs, so a failed delivery stays unacknowledged for broker recovery.
        factory.setDefaultRequeueRejected(true);
        factory.setMissingQueuesFatal(false);
        factory.setErrorHandler(loggingErrorHandler());
        // A bounded graceful stop: in-flight work may finish, then the channel closes and any
        // still-unacknowledged delivery returns to the queue.
        factory.setContainerCustomizer(
                container -> container.setShutdownTimeout(properties.shutdownTimeout().toMillis()));
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    ConsumerInstrumentation consumerInstrumentation() {
        return ConsumerInstrumentation.NOOP;
    }

    private static ErrorHandler loggingErrorHandler() {
        // Only the exception class name is logged: an unexpected conversion/unhandled failure could
        // otherwise carry untrusted message content, and no stack trace is needed to see the state
        // (the delivery stays unacknowledged and is retried by the retained-delivery loop).
        return throwable -> log.error("Notification consumer delivery failed outside the bounded retry "
                + "policy ({}); the delivery stays unacknowledged for broker recovery",
                throwable.getClass().getSimpleName());
    }
}

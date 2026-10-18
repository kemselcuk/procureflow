package com.procureflow.worker.relay;

import com.procureflow.worker.config.RabbitTopology;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes persistent schema-v1 envelopes to the durable topic exchange with mandatory routing and
 * publisher confirms. A confirmation alone is insufficient: an unroutable mandatory message may still
 * be acknowledged, so any returned message classifies as a retryable failure.
 */
@Component
public class OutboxPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final RelayProperties properties;
    private final RelayInstrumentation instrumentation;

    public OutboxPublisher(RabbitTemplate rabbitTemplate, RelayProperties properties,
                           RelayInstrumentation instrumentation) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.instrumentation = instrumentation;
    }

    public PublishResult publish(ClaimedEvent event) {
        Message message = message(event);
        CorrelationData correlation = new CorrelationData(event.eventId() + ":" + event.leaseToken());
        try {
            rabbitTemplate.send(RabbitTopology.EXCHANGE, event.eventType(), message, correlation);
        } catch (AmqpException e) {
            return PublishResult.retryable(PublishFailure.BROKER_UNREACHABLE);
        }
        instrumentation.afterSendRequested(event.eventId(), correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            // The broker guarantees the return precedes the confirmation, and Spring AMQP documents
            // getReturned() as populated before the future completes. A return always defeats an ack.
            if (correlation.getReturned() != null) {
                return PublishResult.retryable(PublishFailure.BROKER_RETURNED);
            }
            if (!confirm.isAck()) {
                return PublishResult.retryable(PublishFailure.BROKER_NACK);
            }
            return PublishResult.success();
        } catch (TimeoutException e) {
            return PublishResult.retryable(PublishFailure.PUBLISH_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PublishResult.retryable(PublishFailure.PUBLISH_TIMEOUT);
        } catch (ExecutionException e) {
            return PublishResult.retryable(PublishFailure.BROKER_NACK);
        }
    }

    private static Message message(ClaimedEvent event) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setMessageId(event.eventId().toString());
        messageProperties.setCorrelationId(event.correlationId().toString());
        messageProperties.setType(event.eventType());
        messageProperties.setHeader("schemaVersion", 1);
        return new Message(event.envelopeJson().getBytes(StandardCharsets.UTF_8), messageProperties);
    }
}

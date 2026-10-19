package com.procureflow.worker.consumer;

import com.procureflow.worker.config.ConsumerProperties;
import com.procureflow.worker.config.RabbitTopology;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Manual-acknowledgement listener on the shared durable notification queue. It is enabled by
 * default ({@code worker.consumer.enabled} missing or {@code true}) and can be disabled for relay
 * tests or a deployment where only the relay runs.
 *
 * <p>The delivery is acknowledged only when the processor reports committed normal effects or a
 * committed durable quarantine record with terminal attempt evidence. Any other outcome retains the
 * same delivery: the listener waits for a capped, interruptible backoff and processes it again, so a
 * database outage that outlives the processor's in-process attempt budget recovers automatically
 * when the database returns, without a worker, channel or container restart. The retained delivery
 * occupies one bounded prefetch slot; the wait performs no database or broker work and never
 * requeues or drops the message. A container stop interrupts the wait and leaves the delivery
 * unacknowledged, so the broker requeues it on the channel close.
 */
@Component
@ConditionalOnProperty(name = "worker.consumer.enabled", havingValue = "true", matchIfMissing = true)
class NotificationQueueListener {
    /** Stable listener id for the test/operator registry lookup. */
    static final String LISTENER_ID = "notification-queue-consumer";

    private static final Logger log = LoggerFactory.getLogger(NotificationQueueListener.class);

    private final ConsumerMessageProcessor processor;
    private final ConsumerProperties properties;

    NotificationQueueListener(ConsumerMessageProcessor processor, ConsumerProperties properties) {
        this.processor = processor;
        this.properties = properties;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitTopology.QUEUE,
            containerFactory = "consumerRabbitListenerContainerFactory")
    void onMessage(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        while (true) {
            boolean acknowledge;
            try {
                acknowledge = processor.process(message);
            } catch (RuntimeException e) {
                // Never log the throwable or the untrusted message: an unexpected conversion or parse
                // failure could embed raw body content. The class name is enough for diagnosis.
                log.error("Unhandled consumer failure ({}); the delivery stays unacknowledged and will be "
                        + "retried with backoff", e.getClass().getSimpleName());
                acknowledge = false;
            }
            if (acknowledge) {
                acknowledgeSafely(channel, deliveryTag);
                return;
            }
            if (!interruptibleBackoff()) {
                return;
            }
        }
    }

    /**
     * Capped, interruptible wait before the retained delivery is processed again. The processor
     * already applied its bounded attempt budget, so this wait only paces whole recovery cycles; no
     * database connection or broker operation is held while waiting.
     */
    private boolean interruptibleBackoff() {
        try {
            Thread.sleep(properties.retryDelayMillis(properties.maxAttempts()));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Consumer recovery wait interrupted; leaving the delivery unacknowledged for broker "
                    + "recovery");
            return false;
        }
    }

    private void acknowledgeSafely(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException | RuntimeException e) {
            // The effects (or the quarantine record) are committed; the broker will redeliver and the
            // inbox uniqueness makes the retry a no-op.
            log.warn("Broker ACK failed after a committed outcome ({}); the delivery will be redelivered and "
                    + "deduplicated", e.getClass().getSimpleName());
        }
    }
}

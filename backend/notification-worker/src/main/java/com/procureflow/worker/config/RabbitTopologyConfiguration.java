package com.procureflow.worker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable single-broker topology: a topic exchange, one accumulating notification queue and the
 * {@code request.*} binding. The queue is classic (durable, non-exclusive, non-auto-delete); quorum
 * replication is deliberately not claimed on a single broker, and switching the queue type would be
 * a provisioning change because RabbitMQ rejects redeclaration with different arguments.
 */
@Configuration
class RabbitTopologyConfiguration {
    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyConfiguration.class);

    @Bean
    Declarables notificationTopology() {
        TopicExchange exchange = new TopicExchange(RabbitTopology.EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(RabbitTopology.QUEUE)
                .withArgument("x-queue-type", "classic")
                .build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(RabbitTopology.BINDING_PATTERN);
        return new Declarables(exchange, queue, binding);
    }

    @Bean
    RabbitTemplateCustomizer relayRabbitTemplateCustomizer() {
        return template -> {
            template.setMandatory(true);
            template.setReturnsCallback(returned -> log.warn(
                    "Mandatory message returned: messageId={} exchange={} routingKey={} replyCode={} replyText={}",
                    returned.getMessage().getMessageProperties().getMessageId(), returned.getExchange(),
                    returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
        };
    }
}

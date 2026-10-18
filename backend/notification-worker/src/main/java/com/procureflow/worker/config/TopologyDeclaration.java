package com.procureflow.worker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Declares the durable topology as soon as the process starts. The RabbitAdmin connection listener
 * re-declares it on every new connection, which is how transient broker disconnections recover.
 * A broker that is unavailable at startup must not prevent the worker from running: declarations are
 * retried by the same listener when connectivity returns.
 */
@Component
class TopologyDeclaration {
    private static final Logger log = LoggerFactory.getLogger(TopologyDeclaration.class);

    private final AmqpAdmin amqpAdmin;

    TopologyDeclaration(AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    @EventListener(ApplicationStartedEvent.class)
    void declareAtStartup() {
        if (amqpAdmin instanceof RabbitAdmin rabbitAdmin) {
            try {
                rabbitAdmin.initialize();
            } catch (RuntimeException e) {
                log.warn("Rabbit topology declaration deferred until the broker is reachable: {}",
                        e.getClass().getSimpleName());
            }
        }
    }
}

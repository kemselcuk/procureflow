package com.procureflow.worker.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.procureflow.worker.support.EmailServiceFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The single runtime flag removes every SMTP bean (runner, sender, scheduler) while the database
 * lifecycle store and the worker readiness contract stay intact: Mailpit being disabled or down
 * must never mark the worker or the business application unready.
 */
@SpringBootTest
class EmailDisabledContextIT {
    @Autowired
    ApplicationContext context;
    @Autowired
    Environment environment;

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmailServiceFixture::jdbcUrl);
        registry.add("spring.datasource.username", () -> EmailServiceFixture.WORKER_USER);
        registry.add("spring.datasource.password", EmailServiceFixture::workerPassword);
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(EmailServiceFixture.closedLoopbackPort()));
        registry.add("spring.rabbitmq.connection-timeout", () -> "PT0.2S");
        registry.add("worker.relay.scheduled", () -> "false");
        registry.add("worker.consumer.enabled", () -> "false");
        registry.add("worker.consumer.scheduled", () -> "false");
        registry.add("worker.smtp.enabled", () -> "false");
        registry.add("worker.smtp.scheduled", () -> "false");
    }

    @Test
    void disablingSmtpRemovesTheRunnerAndKeepsReadinessIndependent() {
        assertThat(context.getBeanNamesForType(EmailJobRunner.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SmtpEmailSender.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EmailJobScheduler.class)).isEmpty();
        assertThat(context.getBean(EmailJobStore.class)).as("the database lifecycle store stays available")
                .isNotNull();
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .as("worker readiness must not include an SMTP indicator")
                .isEqualTo("readinessState,db");
    }
}

package com.procureflow.worker.config;

import com.procureflow.worker.email.EmailInstrumentation;
import com.procureflow.worker.email.EmailJobRunner;
import com.procureflow.worker.email.EmailJobScheduler;
import com.procureflow.worker.email.EmailJobStore;
import com.procureflow.worker.email.SmtpEmailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local SMTP capture wiring. The whole slice is behind {@code worker.smtp.enabled} (default true)
 * so relay/consumer regression runs can disable every SMTP bean with one flag; disabling it never
 * affects the business app, the broker topology or password semantics. The worker's readiness
 * endpoint intentionally does not include an SMTP health indicator: Mailpit being down must back
 * off and recover, not mark the worker or the application unready.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmtpProperties.class)
@ConditionalOnProperty(name = "worker.smtp.enabled", havingValue = "true", matchIfMissing = true)
class SmtpConfiguration {

    @Bean
    SmtpEmailSender smtpEmailSender(SmtpProperties properties) {
        return new SmtpEmailSender(properties);
    }

    @Bean
    EmailJobRunner emailJobRunner(EmailJobStore store, SmtpEmailSender sender, SmtpProperties properties,
                                  EmailInstrumentation instrumentation) {
        return new EmailJobRunner(store, sender, properties, instrumentation);
    }

    @Bean
    EmailInstrumentation emailInstrumentation() {
        return EmailInstrumentation.NOOP;
    }

    @Bean
    @ConditionalOnProperty(name = "worker.smtp.scheduled", havingValue = "true", matchIfMissing = true)
    EmailJobScheduler emailJobScheduler(EmailJobRunner runner) {
        return new EmailJobScheduler(runner);
    }
}

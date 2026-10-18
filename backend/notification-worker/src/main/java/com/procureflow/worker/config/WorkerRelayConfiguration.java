package com.procureflow.worker.config;

import com.procureflow.worker.relay.RelayInstrumentation;
import com.procureflow.worker.relay.RelayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RelayProperties.class)
class WorkerRelayConfiguration {
    @Bean
    RelayInstrumentation relayInstrumentation() {
        return RelayInstrumentation.NOOP;
    }
}

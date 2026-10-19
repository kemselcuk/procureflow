package com.procureflow.worker.consumer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the production no-op instrumentation with the test crash hook. Imported explicitly by
 * {@link ConsumerIntegrationTestBase} so the override does not depend on nested-config detection.
 */
@TestConfiguration(proxyBeanMethods = false)
class ConsumerTestInstrumentationConfiguration {

    @Bean
    @Primary
    ConsumerInstrumentation testConsumerInstrumentation() {
        return ConsumerTestHooks::afterMaterializationCommit;
    }
}

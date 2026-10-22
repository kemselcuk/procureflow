package com.procureflow.app.support;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only configuration that wraps the real pooled DataSource in {@link CountingDataSource} so
 * query-count tests observe actual executed statements. Import it only from tests that need the
 * counter; it never changes production behavior.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CountingDataSourceConfiguration {
    private static volatile CountingDataSource counting;

    @Bean
    static BeanPostProcessor countingDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource source && !(bean instanceof CountingDataSource)) {
                    counting = new CountingDataSource(source);
                    return counting;
                }
                return bean;
            }
        };
    }

    public static CountingDataSource counting() {
        var current = counting;
        if (current == null) throw new IllegalStateException("CountingDataSource was not installed");
        return current;
    }
}

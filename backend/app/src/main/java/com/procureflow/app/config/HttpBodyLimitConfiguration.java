package com.procureflow.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class HttpBodyLimitConfiguration {
    /**
     * The documented create/edit form is title (max 200 characters) plus justification (max 2000
     * characters); 16 KiB leaves roughly six times that worst case. nginx additionally caps its
     * own accepted body at 64k. The registration runs last so the security filter chain still
     * authenticates and authorizes before any body bytes are read.
     */
    @Bean
    FilterRegistrationBean<JsonRequestBodyLimitFilter> jsonRequestBodyLimitFilter(
            @Value("${app.http.max-json-body-bytes:16384}") int maxJsonBodyBytes) {
        var registration = new FilterRegistrationBean<>(new JsonRequestBodyLimitFilter(maxJsonBodyBytes));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}

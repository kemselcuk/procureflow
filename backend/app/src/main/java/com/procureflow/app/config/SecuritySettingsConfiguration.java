package com.procureflow.app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the OIDC/security settings for every application context, including the one-shot
 * migrate context that intentionally has no servlet web application and therefore no
 * {@link SecurityConfiguration} filter chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecuritySettings.class)
public class SecuritySettingsConfiguration {
}

package com.procureflow.app.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record SecuritySettings(@NotBlank String issuer, @NotBlank String jwkSetUri,
                               @NotBlank String audience, @NotEmpty List<String> allowedOrigins) {}

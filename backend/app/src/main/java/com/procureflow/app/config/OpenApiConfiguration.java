package com.procureflow.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfiguration {
    @Bean
    OpenAPI procureFlowApi() {
        return new OpenAPI().info(new Info()
                .title("ProcureFlow API")
                .version("0.1.0")
                .description("Identity/company, request lifecycle and budget-checked approval APIs. Notification delivery remains planned."));
    }
}

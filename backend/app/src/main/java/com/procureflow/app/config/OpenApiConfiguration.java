package com.procureflow.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI procureFlowApi() {
        return new OpenAPI().info(new Info()
                .title("ProcureFlow API")
                .version("0.1.0")
                .description("Foundation only. Purchase and identity features are not implemented yet."));
    }
}

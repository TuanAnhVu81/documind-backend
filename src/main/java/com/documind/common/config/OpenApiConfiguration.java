package com.documind.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI documindOpenApi() {
        return new OpenAPI().info(new Info().title("DocuMind Backend")
                .version("0.0.1-SNAPSHOT")
                .description("Phase 1: local infrastructure. Business APIs are not implemented yet."));
    }
}

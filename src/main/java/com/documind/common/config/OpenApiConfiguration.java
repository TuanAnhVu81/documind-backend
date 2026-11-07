package com.documind.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI documindOpenApi() {
        return new OpenAPI().info(new Info().title("DocuMind Backend")
                .version("0.0.1-SNAPSHOT")
                .description("Các API xác thực, tài liệu, hội thoại và thống kê. Các yêu cầu POST xác thực cần Origin, cookie CSRF và X-CSRF-TOKEN. Các API nghiệp vụ dùng Bearer JWT."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}

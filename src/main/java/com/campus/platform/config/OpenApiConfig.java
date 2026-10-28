package com.campus.platform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 文档配置。访问地址：/swagger-ui.html，JSON：/v3/api-docs。
 * 生产环境可通过 APP_SWAGGER_ENABLED=false 关闭。
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI campusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("高校 AI 综合服务平台 API")
                        .version("v1")
                        .description("统一响应结构见 ApiResponse；认证方式：Authorization: Bearer <accessToken>"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}

package com.parkview.ruleengine.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. The spec is generated from the controllers/DTOs (annotate them with
 * {@code @Tag}, {@code @Operation}, {@code @Schema}); it is exposed only when
 * {@code springdoc.api-docs.enabled=true} (local/e2e), never in production by default.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI openApi(@Value("${spring.application.name}") String name,
                    @Value("${parkview.docs.description:}") String description,
                    @Value("${parkview.docs.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info().title("Park & View - " + name).version(version).description(description))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}

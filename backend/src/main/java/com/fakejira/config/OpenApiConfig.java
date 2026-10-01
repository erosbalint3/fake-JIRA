package com.fakejira.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description of the REST API (/v3/api-docs) and the interactive explorer (/api-docs). Calls are
 * authenticated with a sign-in token or, better for scripts, a personal API token from the profile page.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI fakeJiraOpenApi() {
        return new OpenAPI()
                .info(new Info().title("FakeJIRA API").version("5.0.0")
                        .description("""
                                Everything the web app does is available here. Authenticate with a personal API token \
                                (Profile → API tokens) sent as `Authorization: Bearer fj_…`. Endpoints under \
                                `/api/public` need no token.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("A personal API token (fj_…) or a sign-in token.")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    @Bean
    GroupedOpenApi restApi() {
        return GroupedOpenApi.builder().group("fakejira").pathsToMatch("/api/**")
                .pathsToExclude("/api/events", "/api/metrics").build();
    }
}

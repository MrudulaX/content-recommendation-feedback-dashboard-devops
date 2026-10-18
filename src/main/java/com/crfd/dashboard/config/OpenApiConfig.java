package com.crfd.dashboard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI / Swagger UI metadata - the machine readable half of the API catalogue. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI crfdOpenAPI(@Value("${crfd.environment:local}") String environment,
                               @Value("${info.app.version:unknown}") String version) {
        return new OpenAPI().info(new Info()
                .title("Content Recommendation Feedback Dashboard API")
                .version(version)
                .description("""
                        MVP API for the Content Recommendation Feedback Dashboard.

                        Capabilities: data/event entry, searchable dashboard, summary indicators,
                        status drill-down and the alert/exception view.

                        Environment: **%s**
                        """.formatted(environment))
                .contact(new Contact().name("CRFD Team").email("crfd-team@example.com"))
                .license(new License().name("MIT")));
    }
}

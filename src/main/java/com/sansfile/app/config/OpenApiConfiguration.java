package com.sansfile.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tech.jhipster.config.JHipsterConstants;

@Configuration
@Profile(JHipsterConstants.SPRING_PROFILE_API_DOCS)
public class OpenApiConfiguration {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";
        return new OpenAPI()
            .info(
                new Info()
                    .title("SansFile API REST")
                    .description(
                        "Documentation complète de l'API Backend SansFile (Authentification OTP SMS, Salons, File d'attente Tickets, Boutique, Favoris et Administration)."
                    )
                    .version("1.0.0")
                    .contact(new Contact().name("Support SansFile").email("contact@sansfile.com").url("https://sansfile.com"))
                    .license(new License().name("Propriétaire").url("https://sansfile.com"))
            )
            .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
            .components(
                new Components().addSecuritySchemes(
                    securitySchemeName,
                    new SecurityScheme().name(securitySchemeName).type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                )
            );
    }

    @Bean
    public GroupedOpenApi sansfileAllGroupedOpenAPI() {
        return GroupedOpenApi.builder()
            .group("1-sansfile-complete")
            .displayName("🚀 Toutes les API SansFile (Complète)")
            .packagesToScan("com.sansfile.app.web.rest", "com.sansfile.app.web.rest.custom")
            .pathsToMatch("/api/**")
            .build();
    }

    @Bean
    public GroupedOpenApi sansfileCustomGroupedOpenAPI() {
        return GroupedOpenApi.builder()
            .group("2-sansfile-metier")
            .displayName("⭐ API Métier (OTP, Tickets, Commandes, Salons, Admin)")
            .packagesToScan("com.sansfile.app.web.rest.custom")
            .pathsToMatch("/api/**")
            .build();
    }
}

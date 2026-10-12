package com.rydex.driver.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WebConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Rydex driver-service API").version("v1")
                        .description("Driver profiles, availability, location heartbeats, ride offers and the "
                                + "admin matching views. Sign in on ride-service (POST /api/auth/login on :8081), "
                                + "then click Authorize and paste the accessToken."))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}

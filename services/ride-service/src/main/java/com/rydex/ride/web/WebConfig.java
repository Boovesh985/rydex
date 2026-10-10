package com.rydex.ride.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimiter;

    WebConfig(RateLimitInterceptor rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimiter).addPathPatterns("/api/**");
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Rydex ride-service API").version("v1")
                        .description("Auth, places, fare estimates, booking and the ride lifecycle. "
                                + "Sign in via POST /api/auth/login, then click Authorize and paste the accessToken."))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}

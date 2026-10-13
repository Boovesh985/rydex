package com.rydex.payment.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Rydex payment-service API").version("v1")
                        .description("Ride payments and refunds. Payments are created from ride events (a completed "
                                + "ride, or a cancellation after the driver arrived); riders read and pay them here, "
                                + "admins refund them. Sign in on ride-service, then click Authorize and paste the "
                                + "accessToken."))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }

    /** /dev/checkout.html: pay a ride in Razorpay test mode before the frontend exists (off in production). */
    @Bean
    @ConditionalOnProperty(name = "rydex.payments.dev-pages", havingValue = "true")
    WebMvcConfigurer devPages() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/dev/**").addResourceLocations("classpath:/dev-pages/");
            }
        };
    }
}

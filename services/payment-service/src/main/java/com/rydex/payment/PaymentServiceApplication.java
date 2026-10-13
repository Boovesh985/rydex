package com.rydex.payment;

import com.rydex.messaging.EnableMessaging;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Charges riders (ADR 0012): the fare of every completed ride and the fee of a late cancellation,
 * through the mock provider or Razorpay test mode, and refunds them. Consumes {@code ride.events},
 * produces {@code payment.events}.
 */
@SpringBootApplication
@EnableMessaging
@EnableScheduling
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

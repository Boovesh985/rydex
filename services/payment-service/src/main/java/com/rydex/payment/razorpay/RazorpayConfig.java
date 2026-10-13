package com.rydex.payment.razorpay;

import com.rydex.payment.config.PaymentProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay beans, present whenever keys are configured (even if new payments go to the mock), so
 * payments started with Razorpay can still be checked and refunded.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${rydex.payments.razorpay.key-id:}'.isBlank()")
class RazorpayConfig {

    @Bean
    RazorpayClient razorpayClient(PaymentProperties props, JsonMapper json) {
        PaymentProperties.Razorpay rzp = props.razorpay();
        rzp.validate();
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(rzp.connectTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(rzp.readTimeout());
        String basic = Base64.getEncoder()
                .encodeToString((rzp.keyId() + ":" + rzp.keySecret()).getBytes(StandardCharsets.UTF_8));
        return new RazorpayClient(RestClient.builder()
                .requestFactory(factory)
                .baseUrl(rzp.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build(), json);
    }

    @Bean
    RazorpayProvider razorpayProvider(RazorpayClient client) {
        return new RazorpayProvider(client);
    }
}

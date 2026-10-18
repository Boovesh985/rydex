package com.rydex.ride.ai;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class AiClientConfig {

    @Bean
    AiClient aiClient(AiProperties props) {
        // HTTP/1.1: no h2c upgrade round trip on a call that has 300 ms in all
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.timeout());
        return new AiClient(RestClient.builder().requestFactory(factory).baseUrl(props.baseUrl()).build());
    }
}

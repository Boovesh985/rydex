package com.rydex.driver.matching;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class RoutingConfig {

    @Bean
    OsrmTableClient osrmTableClient(RoutingProperties props) {
        // Same client settings as ride-service (see GeoClientsConfig there): HTTP/1.1 because the
        // JDK's HTTP/2 upgrade to the public OSM servers can stall, and no compression because
        // OSRM's raw deflate stream is rejected by the JDK inflater.
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return new OsrmTableClient(RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.osrmBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
                .build());
    }
}

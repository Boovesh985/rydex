package com.rydex.tracking.config;

import com.rydex.presence.PresenceStore;
import com.rydex.tracking.location.OsrmRouteClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ClientsConfig {

    @Bean
    PresenceStore presenceStore(StringRedisTemplate redis, PresenceProperties props) {
        return new PresenceStore(redis, props.staleAfter(), props.minLocationInterval());
    }

    @Bean
    RestClient rideServiceRestClient(ServicesProperties props) {
        return RestClient.builder()
                .requestFactory(factory(props.connectTimeout(), props.readTimeout()))
                .baseUrl(props.rideServiceUrl())
                .build();
    }

    @Bean
    RestClient driverServiceRestClient(ServicesProperties props) {
        return RestClient.builder()
                .requestFactory(factory(props.connectTimeout(), props.readTimeout()))
                .baseUrl(props.driverServiceUrl())
                .build();
    }

    /** Same settings as the other services' OSRM clients (see ride-service's GeoClientsConfig). */
    @Bean
    OsrmRouteClient osrmRouteClient(RoutingProperties props) {
        return new OsrmRouteClient(RestClient.builder()
                .requestFactory(factory(props.connectTimeout(), props.readTimeout()))
                .baseUrl(props.osrmBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
                .build());
    }

    /** HTTP/1.1: the JDK's HTTP/2 upgrade to the public OSM servers can stall. */
    private static JdkClientHttpRequestFactory factory(Duration connectTimeout, Duration readTimeout) {
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}

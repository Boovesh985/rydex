package com.rydex.ride.geo;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class GeoClientsConfig {

    @Bean
    OsrmClient osrmClient(GeoProperties props) {
        return new OsrmClient(builder(props).baseUrl(props.osrmBaseUrl()).build());
    }

    @Bean
    NominatimClient nominatimClient(GeoProperties props) {
        return new NominatimClient(builder(props).baseUrl(props.nominatimBaseUrl()).build(), props.countryCodes());
    }

    private static RestClient.Builder builder(GeoProperties props) {
        // HTTP/1.1: the JDK client's HTTP/2 upgrade to the public OSM servers can stall until
        // the connect timeout on some networks; plain JSON GETs gain nothing from HTTP/2.
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
                // Responses are a few KB. OSRM answers "deflate" with a raw stream the JDK
                // inflater rejects ("incorrect header check"), so ask for no compression at all.
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
    }
}

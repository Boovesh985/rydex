package com.rydex.ride.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rydex.common.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeoClientsTest {

    @Test
    void osrmParsesRoutesAndOrdersCoordinatesLngLat() {
        var builder = RestClient.builder().baseUrl("http://osrm");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://osrm/route/v1/driving/80.270700,13.082700;80.282400,13.050000")))
                .andRespond(withSuccess("""
                        {"code":"Ok","routes":[
                          {"distance":5012.4,"duration":901.6,"geometry":"abc"},
                          {"distance":5600,"duration":960,"geometry":"def"}]}
                        """, MediaType.APPLICATION_JSON));

        var routes = new OsrmClient(builder.build()).route(GeoPoint.of(13.0827, 80.2707), GeoPoint.of(13.05, 80.2824));

        assertThat(routes).hasSize(2);
        assertThat(routes.getFirst()).isEqualTo(new RouteResult.Alternative(5012, 902, "abc"));
        server.verify();
    }

    @Test
    void osrmNoRouteIsAnError() {
        var builder = RestClient.builder().baseUrl("http://osrm");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://osrm/route")))
                .andRespond(withSuccess("{\"code\":\"NoRoute\",\"routes\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new OsrmClient(builder.build())
                .route(GeoPoint.of(13.0827, 80.2707), GeoPoint.of(13.05, 80.2824)))
                .isInstanceOf(OsrmClient.RoutingException.class);
    }

    @Test
    void nominatimSearchRestrictsCountryAndBiasesTowardsUser() {
        var builder = RestClient.builder().baseUrl("http://nominatim");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://nominatim/search")))
                .andExpect(queryParam("countrycodes", "in"))
                .andExpect(queryParam("viewbox", "79.7707,13.5827,80.7707,12.5827"))
                .andRespond(withSuccess("""
                        [{"name":"","display_name":"Marina Beach, Chennai, Tamil Nadu, India","lat":"13.05","lon":"80.2824"}]
                        """, MediaType.APPLICATION_JSON));

        var places = new NominatimClient(builder.build(), "in").search("marina", 13.0827, 80.2707);

        assertThat(places).containsExactly(
                new Place("Marina Beach", "Marina Beach, Chennai, Tamil Nadu, India", 13.05, 80.2824));
        server.verify();
    }

    @Test
    void nominatimReverseErrorIsEmpty() {
        var builder = RestClient.builder().baseUrl("http://nominatim");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://nominatim/reverse")))
                .andRespond(withSuccess("{\"error\":\"Unable to geocode\"}", MediaType.APPLICATION_JSON));

        assertThat(new NominatimClient(builder.build(), "in").reverse(0, 0)).isEmpty();
    }
}

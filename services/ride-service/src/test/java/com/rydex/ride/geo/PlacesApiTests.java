package com.rydex.ride.geo;

import static com.rydex.ride.TestApi.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.rydex.ride.IntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlacesApiTests extends IntegrationTest {

    @Test
    void searchIsCachedInRedis() {
        String rider = token(api().register("RIDER"));
        String query = "marina beach " + UUID.randomUUID().toString().substring(0, 8);
        given(nominatim.search(eq(query), any(), any()))
                .willReturn(List.of(new Place("Marina Beach", "Marina Beach, Chennai, Tamil Nadu", 13.05, 80.2824)));

        var first = api().get("/api/places/search?q=" + query, rider);
        var second = api().get("/api/places/search?q=" + query.toUpperCase(), rider);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().get(0).path("name").asString()).isEqualTo("Marina Beach");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        verify(nominatim, times(1)).search(eq(query), any(), any());
    }

    @Test
    void routeBetweenTwoPointsForAnySignedInUser() {
        String driver = token(api().register("DRIVER"));
        given(osrm.route(any(), any())).willReturn(List.of(new RouteResult.Alternative(4_321, 654, "abc_poly")));

        var res = api().get("/api/places/route?fromLat=13.0101&fromLng=80.2101&toLat=13.0202&toLng=80.2202", driver);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("distanceM").asInt()).isEqualTo(4_321);
        assertThat(res.getBody().path("durationS").asInt()).isEqualTo(654);
        assertThat(res.getBody().path("polyline").asString()).isEqualTo("abc_poly");
        assertThat(res.getBody().path("source").asString()).isEqualTo("OSRM");
        assertThat(api().get("/api/places/route?fromLat=99&fromLng=80&toLat=13&toLng=80", driver)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(api().get("/api/places/route?fromLat=13&fromLng=80&toLat=13&toLng=80.1", null)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void reverseGeocodes() {
        String rider = token(api().register("RIDER"));
        given(nominatim.reverse(anyDouble(), anyDouble()))
                .willReturn(Optional.of(new Place("Chennai Central", "Chennai Central, Park Town", 13.0827, 80.2707)));

        var res = api().get("/api/places/reverse?lat=13.08271&lng=80.27071", rider);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().path("address").asString()).startsWith("Chennai Central");
    }

    @Test
    void nominatimFailureBecomes503() {
        String rider = token(api().register("RIDER"));
        given(nominatim.search(eq("broken query"), any(), any())).willThrow(new IllegalStateException("boom"));

        var res = api().get("/api/places/search?q=broken query", rider);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void queryMustBeLongEnough() {
        String rider = token(api().register("RIDER"));

        assertThat(api().get("/api/places/search?q=ab", rider).getStatusCode().value()).isEqualTo(400);
    }
}

package com.rydex.ride.places;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.ride.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class SavedPlacesApiTests extends IntegrationTest {

    static final Map<String, Object> ADYAR = Map.of("lat", 13.0012, "lng", 80.2565, "address", "Gandhi Nagar, Adyar");
    static final Map<String, Object> TIDEL = Map.of("lat", 12.9894, "lng", 80.2486, "address", "Tidel Park, Taramani");
    static final Map<String, Object> BESANT = Map.of("lat", 13.0002, "lng", 80.2668, "address", "Besant Nagar");

    @Autowired
    JdbcClient jdbc;

    @Test
    void aRiderSavesMovesAndForgetsHomeAndWork() {
        JsonNode auth = api().register("RIDER");
        String rider = token(auth);

        assertThat(api().get("/api/me/places", rider).getBody()).isEmpty();
        var home = api().put("/api/me/places/HOME", rider, ADYAR);
        assertThat(home.getStatusCode().value()).isEqualTo(200);
        assertThat(home.getBody().path("place").path("address").asString()).isEqualTo("Gandhi Nagar, Adyar");
        api().put("/api/me/places/WORK", rider, TIDEL);
        api().put("/api/me/places/HOME", rider, BESANT); // moved house

        JsonNode list = api().get("/api/me/places", rider).getBody();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("kind").asString()).isEqualTo("HOME");
        assertThat(list.get(0).path("place").path("address").asString()).isEqualTo("Besant Nagar");
        assertThat(list.get(0).path("place").path("lat").asDouble()).isEqualTo(13.0002);
        assertThat(list.get(1).path("kind").asString()).isEqualTo("WORK");

        // personal data: the database holds ciphertext only
        UUID user = userId(auth);
        String stored = jdbc.sql("SELECT place_enc FROM saved_places WHERE user_id = :u AND kind = 'HOME'")
                .param("u", user).query(String.class).single();
        assertThat(stored).doesNotContain("Besant").doesNotContain("13.0002");

        assertThat(api().delete("/api/me/places/WORK", rider).getStatusCode().value()).isEqualTo(204);
        assertThat(api().delete("/api/me/places/WORK", rider).getStatusCode().value()).isEqualTo(204); // already gone
        assertThat(api().get("/api/me/places", rider).getBody()).hasSize(1);
    }

    @Test
    void placesAreEachRidersOwnAndOnlyForRiders() {
        String alice = token(api().register("RIDER"));
        String bob = token(api().register("RIDER"));
        api().put("/api/me/places/HOME", alice, ADYAR);

        assertThat(api().get("/api/me/places", bob).getBody()).isEmpty();
        assertThat(api().put("/api/me/places/HOME", token(api().register("DRIVER")), ADYAR).getStatusCode().value()).isEqualTo(403);
        assertThat(api().put("/api/me/places/GYM", alice, ADYAR).getStatusCode().value()).isEqualTo(400);
        assertThat(api().put("/api/me/places/HOME", alice, Map.of("lat", 123, "lng", 80.2)).getStatusCode().value()).isEqualTo(400);
    }
}

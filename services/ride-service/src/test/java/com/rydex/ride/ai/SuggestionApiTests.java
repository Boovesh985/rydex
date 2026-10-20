package com.rydex.ride.ai;

import static com.rydex.ride.TestApi.token;
import static com.rydex.ride.TestApi.userId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.rydex.common.RedisKeys;
import com.rydex.ride.AiStub;
import com.rydex.ride.IntegrationTest;
import com.rydex.ride.geo.RouteResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SuggestionApiTests extends IntegrationTest {

    static final Map<String, Object> PICKUP = Map.of("lat", 13.0827, "lng", 80.2707, "address", "Chennai Central");
    static final Map<String, Object> DROP = Map.of("lat", 13.0500, "lng", 80.2824, "address", "Marina Beach");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void setUp() {
        given(osrm.route(any(), any())).willReturn(List.of(
                new RouteResult.Alternative(5_000, 900, "fastest_polyline"),
                new RouteResult.Alternative(4_600, 960, "alt_polyline")));
        // other tests route the same trip with their own OSRM stub: drop their cached route (one hour TTL)
        redis.delete(redis.keys(RedisKeys.routeCache("*")));
        breaker().reset();
    }

    @AfterEach
    void tearDown() {
        AiStub.down();
        breaker().reset();
    }

    private CircuitBreaker breaker() {
        return breakers.circuitBreaker("ai");
    }

    /** ai-service's answer, with a fresh id. */
    private UUID suggestionAhead() {
        UUID id = UUID.randomUUID();
        AiStub.answer("""
                {"suggestionId": "%s", "recommendedRideType": "AUTO",
                 "bestPickupTime": {"offsetMinutes": 0, "predictedWaitMin": 4, "predictedFare": 120, "savingsVsNow": 0},
                 "alternativePickup": {"lat": 13.0838, "lng": 80.2707, "walkMeters": 150, "predictedWaitMin": 2},
                 "route": {"choice": "alternative_1", "predictedDurationMin": 17, "polyline": "alt_polyline"},
                 "reasons": ["Auto is the lowest fare with a short wait: 3 min away"],
                 "highlights": ["SHORTER_WAIT", "SMARTER_ROUTE"], "confidence": 0.74, "modelVersion": "2026-10-18",
                 "somethingNew": true}
                """.formatted(id), 0);
        return id;
    }

    private JsonNode estimate(String rider) {
        var res = api().post("/api/rides/estimate", rider, Map.of("pickup", PICKUP, "drop", DROP));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    private Map<String, Object> booking(UUID suggestionId) {
        var body = new HashMap<String, Object>(Map.of("pickup", PICKUP, "drop", DROP, "rideType", "AUTO"));
        body.put("suggestionId", suggestionId);
        return body;
    }

    private Map<String, Object> logRow(UUID id) {
        return jdbc.sql("SELECT user_id, accepted, ride_id, model_version, request::text AS request FROM ai_suggestion_log WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    @Test
    void anEstimateCarriesTheSuggestionAndItIsLogged() {
        var auth = api().register("RIDER");
        UUID id = suggestionAhead();

        JsonNode s = estimate(token(auth)).path("suggestion");

        assertThat(s.path("suggestionId").asString()).isEqualTo(id.toString());
        assertThat(s.path("recommendedRideType").asString()).isEqualTo("AUTO");
        assertThat(s.path("alternativePickup").path("walkMeters").asInt()).isEqualTo(150);
        assertThat(s.path("route").path("choice").asString()).isEqualTo("alternative_1");
        assertThat(s.path("highlights").valueStream().map(JsonNode::asString)).containsExactly("SHORTER_WAIT", "SMARTER_ROUTE");
        assertThat(s.path("reasons").get(0).asString()).contains("lowest fare");

        JsonNode sent = json.readTree(AiStub.lastRequest());
        assertThat(sent.path("userId").asString()).isEqualTo(userId(auth).toString());
        assertThat(sent.path("options")).hasSize(6);
        assertThat(sent.path("options").get(0).path("baseFare").decimalValue()).isPositive();
        assertThat(sent.path("routes")).hasSize(2);
        assertThat(sent.path("routes").get(1).path("polyline").asString()).isEqualTo("alt_polyline");
        assertThat(sent.path("maxSurge").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(sent.has("demand") && sent.has("supply") && sent.has("requestedAt")).isTrue();
        assertThat(sent.path("history")).isEmpty();

        var row = logRow(id);
        assertThat(row.get("user_id")).isEqualTo(userId(auth));
        assertThat(row.get("accepted")).isNull();
        assertThat(row.get("model_version")).isEqualTo("2026-10-18");
        assertThat((String) row.get("request")).contains("\"options\"");
    }

    @Test
    void bookingWithTheSuggestionMarksItTaken() {
        String rider = token(api().register("RIDER"));
        UUID id = suggestionAhead();
        estimate(rider);

        var res = api().post("/api/rides", rider, booking(id));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        UUID rideId = UUID.fromString(res.getBody().path("id").asString());
        var row = logRow(id);
        assertThat(row.get("accepted")).isEqualTo(true);
        assertThat(row.get("ride_id")).isEqualTo(rideId);
        assertThat(jdbc.sql("SELECT suggestion_id FROM rides WHERE id = :id").param("id", rideId).query(UUID.class).single())
                .isEqualTo(id);

        // a later "not now" does not undo the booking
        assertThat(api().post("/api/ai/suggestions/" + id + "/feedback", rider, Map.of("accepted", false))
                .getStatusCode().value()).isEqualTo(204);
        assertThat(logRow(id).get("accepted")).isEqualTo(true);
    }

    @Test
    void onlyTheRiderWhoWasShownASuggestionCanUseIt() {
        String shown = token(api().register("RIDER"));
        String other = token(api().register("RIDER"));
        UUID id = suggestionAhead();
        estimate(shown);

        var res = api().post("/api/rides", other, booking(id));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody().path("id").asString()).isNotBlank();
        assertThat(logRow(id).get("accepted")).isNull();
        assertThat(api().post("/api/ai/suggestions/" + id + "/feedback", other, Map.of("accepted", true))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(api().post("/api/ai/suggestions/" + UUID.randomUUID() + "/feedback", shown, Map.of("accepted", true))
                .getStatusCode().value()).isEqualTo(404);
        String driver = token(api().register("DRIVER"));
        assertThat(api().post("/api/ai/suggestions/" + id + "/feedback", driver, Map.of("accepted", true))
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void dismissingASuggestionIsLogged() {
        String rider = token(api().register("RIDER"));
        UUID id = suggestionAhead();
        estimate(rider);

        assertThat(api().post("/api/ai/suggestions/" + id + "/feedback", rider, Map.of("accepted", false))
                .getStatusCode().value()).isEqualTo(204);
        assertThat(logRow(id).get("accepted")).isEqualTo(false);
        assertThat(api().post("/api/ai/suggestions/" + id + "/feedback", rider, Map.of())
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void theEstimateGoesOutWithoutASuggestionWhenAiServiceIsDownOrSlow() {
        String rider = token(api().register("RIDER"));
        AiStub.down();
        JsonNode down = estimate(rider);
        assertThat(down.path("options")).hasSize(6);
        assertThat(down.path("suggestion").isNull()).isTrue();

        suggestionAhead();
        AiStub.answer(AiStubAnswers.slow(), 1_500);
        long started = System.nanoTime();
        JsonNode slow = estimate(rider);
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(slow.path("suggestion").isNull()).isTrue();
        assertThat(slow.path("options")).hasSize(6);
        assertThat(tookMs).isLessThan(1_200);
    }

    @Test
    void afterRepeatedFailuresTheBreakerStopsAsking() {
        String rider = token(api().register("RIDER"));
        AiStub.down();
        for (int i = 0; i < 6; i++) {
            estimate(rider);
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int calls = AiStub.calls();
        suggestionAhead();
        assertThat(estimate(rider).path("suggestion").isNull()).isTrue();
        assertThat(AiStub.calls()).isEqualTo(calls);
    }

    @Test
    void automatedRidersGetNoSuggestion() {
        var auth = api().register("RIDER");
        jdbc.sql("UPDATE users SET simulated = true WHERE id = :id").param("id", userId(auth)).update();
        suggestionAhead();
        int calls = AiStub.calls();

        assertThat(estimate(token(auth)).path("suggestion").isNull()).isTrue();
        assertThat(AiStub.calls()).isEqualTo(calls);
    }

    @Test
    void adminStatsCountSuggestionsShownAndBooked() {
        String admin = token(api().post("/api/auth/login", null, Map.of("email", "admin@rydex.dev", "password", "Rydex@123")).getBody());
        JsonNode before = api().get("/api/admin/stats?hours=1", admin).getBody();
        String rider = token(api().register("RIDER"));
        UUID id = suggestionAhead();
        estimate(rider);
        api().post("/api/rides", rider, booking(id));

        JsonNode after = api().get("/api/admin/stats?hours=1", admin).getBody();
        assertThat(after.path("aiSuggestions").asLong()).isEqualTo(before.path("aiSuggestions").asLong() + 1);
        assertThat(after.path("aiBooked").asLong()).isEqualTo(before.path("aiBooked").asLong() + 1);
    }

    /** A valid answer for calls that must time out before it arrives. */
    static final class AiStubAnswers {
        static String slow() {
            return """
                    {"suggestionId": "%s", "recommendedRideType": "BIKE",
                     "bestPickupTime": {"offsetMinutes": 0, "predictedWaitMin": 4, "predictedFare": 90, "savingsVsNow": 0},
                     "reasons": [], "highlights": [], "confidence": 0.5, "modelVersion": "slow"}
                    """.formatted(UUID.randomUUID());
        }
    }
}

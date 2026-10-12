package com.rydex.driver.driver;

import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.common.geo.Geohash;
import com.rydex.driver.IntegrationTest;
import com.rydex.driver.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DriverApiTests extends IntegrationTest {

    @Test
    void requiresAValidToken() {
        UUID id = UUID.randomUUID();

        assertThat(api().get("/api/drivers/me", null).getStatusCode().value()).isEqualTo(401);
        assertThat(api().get("/api/drivers/me", TestTokens.withIssuer(id, "someone-else")).getStatusCode().value()).isEqualTo(401);
        assertThat(api().get("/api/drivers/me", TestTokens.signedByStranger(id)).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void ridersCannotUseDriverEndpoints() {
        var res = api().get("/api/drivers/me", TestTokens.rider(UUID.randomUUID()));

        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void driverWithoutProfileIsAskedToOnboard() {
        var res = api().get("/api/drivers/me", TestTokens.driver(UUID.randomUUID()));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().path("detail").asString()).contains("PUT /api/drivers/me");
    }

    @Test
    void onboardingCreatesAnOfflineProfile() {
        TestDriver d = newDriver(RideType.COMFORT);

        var me = api().get("/api/drivers/me", d.token()).getBody();
        assertThat(me.path("id").asString()).isEqualTo(d.id().toString());
        assertThat(me.path("name").asString()).isEqualTo("Test Driver");
        assertThat(me.path("vehicleType").asString()).isEqualTo("COMFORT");
        assertThat(me.path("vehicleNumber").asString()).startsWith("TST-");
        assertThat(me.path("status").asString()).isEqualTo("OFFLINE");
        assertThat(me.path("rating").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(me.path("searchable").asBoolean()).isFalse();
    }

    @Test
    void vehicleNumbersAreUniqueIgnoringCase() {
        TestDriver first = newDriver(RideType.ECONOMY);
        String taken = api().get("/api/drivers/me", first.token()).getBody().path("vehicleNumber").asString();

        var res = api().put("/api/drivers/me", TestTokens.driver(UUID.randomUUID()), Map.of(
                "vehicleType", "ECONOMY", "vehicleNumber", taken.toLowerCase(), "vehicleModel", "Tata Tiago"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void rejectsInvalidProfiles() {
        String token = TestTokens.driver(UUID.randomUUID());

        assertThat(api().put("/api/drivers/me", token, Map.of("vehicleType", "SPACESHIP",
                "vehicleNumber", "TN 01 AB 1234", "vehicleModel", "X")).getStatusCode().value()).isEqualTo(400);
        assertThat(api().put("/api/drivers/me", token, Map.of("vehicleType", "XL",
                "vehicleNumber", "<script>", "vehicleModel", "X")).getStatusCode().value()).isEqualTo(400);
        assertThat(api().put("/api/drivers/me", token, Map.of("vehicleType", "XL",
                "vehicleNumber", "TN 01 AB 1234")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void goingOnlineIndexesTheDriverAndOfflineRemovesThem() {
        TestDriver d = newDriver(RideType.ECONOMY);
        String cell = Geohash.encode(CENTER, Geohash.MATCHING_PRECISION);

        var online = api().patch("/api/drivers/me/status", d.token(),
                Map.of("status", "AVAILABLE", "lat", CENTER.lat(), "lng", CENTER.lng()));

        assertThat(online.getStatusCode().value()).isEqualTo(200);
        assertThat(online.getBody().path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(online.getBody().path("searchable").asBoolean()).isTrue();
        assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.ECONOMY), d.id().toString())).isNotNull();
        assertThat(redis.opsForSet().isMember(RedisKeys.supply(cell), d.id().toString())).isTrue();

        var offline = api().patch("/api/drivers/me/status", d.token(), Map.of("status", "OFFLINE"));

        assertThat(offline.getBody().path("status").asString()).isEqualTo("OFFLINE");
        assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.ECONOMY), d.id().toString())).isNull();
        assertThat(redis.opsForSet().isMember(RedisKeys.supply(cell), d.id().toString())).isFalse();
    }

    @Test
    void onlineWithoutLocationBecomesSearchableOnFirstHeartbeat() {
        TestDriver d = newDriver(RideType.XL);
        api().patch("/api/drivers/me/status", d.token(), Map.of("status", "AVAILABLE"));
        assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.XL), d.id().toString())).isNull();

        var res = api().post("/api/drivers/me/location", d.token(), Map.of("lat", CENTER.lat(), "lng", CENTER.lng(), "heading", 90));

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.XL), d.id().toString())).isNotNull();
        var me = api().get("/api/drivers/me", d.token()).getBody();
        assertThat(me.path("searchable").asBoolean()).isTrue();
        assertThat(me.path("lastLocation").path("lat").asDouble()).isEqualTo(CENTER.lat());
    }

    @Test
    void latAndLngMustComeTogether() {
        TestDriver d = newDriver(RideType.ECONOMY);

        var res = api().patch("/api/drivers/me/status", d.token(), Map.of("status", "AVAILABLE", "lat", 13.0));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void driversCannotSetThemselvesOnTrip() {
        TestDriver d = newDriver(RideType.ECONOMY);

        var res = api().patch("/api/drivers/me/status", d.token(), Map.of("status", "ON_TRIP"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void vehicleTypeCannotChangeWhileOnline() {
        TestDriver d = onlineDriver(RideType.ECONOMY, CENTER);

        var res = api().put("/api/drivers/me", d.token(), Map.of(
                "vehicleType", "XL", "vehicleNumber", "TST-" + d.id().toString().substring(0, 8), "vehicleModel", "Innova"));

        assertThat(res.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void locationUpdatesAreThrottled() {
        TestDriver d = onlineDriver(RideType.ECONOMY, CENTER);
        Map<String, Object> body = Map.of("lat", CENTER.lat(), "lng", CENTER.lng());

        assertThat(api().post("/api/drivers/me/location", d.token(), body).getStatusCode().value()).isEqualTo(204);
        var second = api().post("/api/drivers/me/location", d.token(), body);

        assertThat(second.getStatusCode().value()).isEqualTo(429);
        assertThat(second.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void locationRejectsOutOfRangeCoordinates() {
        TestDriver d = newDriver(RideType.ECONOMY);

        var res = api().post("/api/drivers/me/location", d.token(), Map.of("lat", 95, "lng", 80));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void movingAcrossCellsUpdatesTheSupplySets() throws Exception {
        TestDriver d = onlineDriver(RideType.ECONOMY, CENTER);
        GeoPoint far = north(CENTER, 3000);
        String from = Geohash.encode(CENTER, Geohash.MATCHING_PRECISION);
        String to = Geohash.encode(far, Geohash.MATCHING_PRECISION);
        assertThat(to).isNotEqualTo(from);

        Thread.sleep(600); // past the location throttle
        api().post("/api/drivers/me/location", d.token(), Map.of("lat", far.lat(), "lng", far.lng()));

        assertThat(redis.opsForSet().isMember(RedisKeys.supply(from), d.id().toString())).isFalse();
        assertThat(redis.opsForSet().isMember(RedisKeys.supply(to), d.id().toString())).isTrue();
    }

    @Test
    void heartbeatRebuildsAMissingRedisProjection() {
        TestDriver d = onlineDriver(RideType.COMFORT, CENTER);
        redis.execute(connection -> {
            connection.serverCommands().flushAll(); // e.g. Redis restarted without persistence
            return null;
        }, true);

        api().post("/api/drivers/me/location", d.token(), Map.of("lat", CENTER.lat(), "lng", CENTER.lng()));

        assertThat(redis.opsForZSet().score(RedisKeys.driversGeo(RideType.COMFORT), d.id().toString())).isNotNull();
    }
}

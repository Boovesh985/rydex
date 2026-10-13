package com.rydex.driver.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rydex.common.DriverStatus;
import com.rydex.common.RedisKeys;
import com.rydex.common.RideType;
import com.rydex.common.geo.Geohash;
import com.rydex.driver.IntegrationTest;
import com.rydex.presence.DriverLocation;
import com.rydex.presence.PresenceStore;
import com.rydex.presence.PresenceStore.HeartbeatResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PresenceTests extends IntegrationTest {

    @Autowired
    PresenceStore presence;

    @Autowired
    PresenceReaper reaper;

    @Autowired
    PresenceReconciler reconciler;

    private boolean inGeo(RideType type, UUID id) {
        return redis.opsForZSet().score(RedisKeys.driversGeo(type), id.toString()) != null;
    }

    @Test
    void heartbeatWithoutProjectionAsksForResync() {
        UUID id = UUID.randomUUID();

        assertThat(presence.heartbeat(id, CENTER.lat(), CENTER.lng(), null, null, Instant.now()))
                .isEqualTo(HeartbeatResult.STATE_MISSING);
        assertThat(inGeo(RideType.ECONOMY, id)).isFalse();
    }

    @Test
    void offlineAndOnTripDriversAreNeverIndexed() {
        UUID offline = UUID.randomUUID();
        UUID onTrip = UUID.randomUUID();
        presence.setStatus(offline, DriverStatus.OFFLINE, RideType.ECONOMY);
        presence.setStatus(onTrip, DriverStatus.ON_TRIP, RideType.ECONOMY);

        assertThat(presence.heartbeat(offline, CENTER.lat(), CENTER.lng(), null, null, Instant.now()))
                .isEqualTo(HeartbeatResult.NOT_AVAILABLE);
        assertThat(presence.heartbeat(onTrip, CENTER.lat(), CENTER.lng(), 45.0, 8.5, Instant.now()))
                .isEqualTo(HeartbeatResult.NOT_AVAILABLE);

        assertThat(inGeo(RideType.ECONOMY, offline)).isFalse();
        assertThat(inGeo(RideType.ECONOMY, onTrip)).isFalse();
        // the position is still tracked (the rider follows the on-trip driver)
        DriverLocation loc = presence.location(onTrip).orElseThrow();
        assertThat(loc.heading()).isEqualTo(45.0);
        assertThat(loc.speed()).isEqualTo(8.5);
        assertThat(loc.geohash()).isEqualTo(Geohash.encode(CENTER, Geohash.MATCHING_PRECISION));
    }

    @Test
    void availableDriverIsIndexedUnderTheirRideTypeOnly() {
        UUID id = UUID.randomUUID();
        presence.setStatus(id, DriverStatus.AVAILABLE, RideType.COMFORT);

        assertThat(presence.heartbeat(id, CENTER.lat(), CENTER.lng(), null, null, Instant.now()))
                .isEqualTo(HeartbeatResult.INDEXED);

        assertThat(inGeo(RideType.COMFORT, id)).isTrue();
        assertThat(inGeo(RideType.ECONOMY, id)).isFalse();
        assertThat(inGeo(RideType.XL, id)).isFalse();
        assertThat(presence.supply(Geohash.encode(CENTER, Geohash.MATCHING_PRECISION))).isEqualTo(1);
    }

    @Test
    void locationExpiresWithTheStalenessWindow() {
        UUID id = UUID.randomUUID();
        presence.setStatus(id, DriverStatus.AVAILABLE, RideType.ECONOMY);
        presence.heartbeat(id, CENTER.lat(), CENTER.lng(), null, null, Instant.now());

        Long ttlMs = redis.getExpire(RedisKeys.driverLocation(id), java.util.concurrent.TimeUnit.MILLISECONDS);

        assertThat(ttlMs).isBetween(25_000L, 30_000L);
    }

    @Test
    void reaperEvictsSilentDriversButKeepsTheirStatus() {
        UUID silent = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        presence.setStatus(silent, DriverStatus.AVAILABLE, RideType.ECONOMY);
        presence.setStatus(fresh, DriverStatus.AVAILABLE, RideType.ECONOMY);
        presence.heartbeat(silent, CENTER.lat(), CENTER.lng(), null, null, Instant.now().minus(Duration.ofSeconds(45)));
        presence.heartbeat(fresh, CENTER.lat(), CENTER.lng(), null, null, Instant.now());

        assertThat(reaper.reap()).isEqualTo(1);

        assertThat(inGeo(RideType.ECONOMY, silent)).isFalse();
        assertThat(inGeo(RideType.ECONOMY, fresh)).isTrue();
        assertThat(presence.supply(Geohash.encode(CENTER, Geohash.MATCHING_PRECISION))).isEqualTo(1);
        assertThat(presence.status(silent)).contains(DriverStatus.AVAILABLE);

        // the next heartbeat makes the driver searchable again
        presence.heartbeat(silent, CENTER.lat(), CENTER.lng(), null, null, Instant.now());
        assertThat(inGeo(RideType.ECONOMY, silent)).isTrue();
    }

    @Test
    void evictionLosesToANewerHeartbeat() {
        UUID id = UUID.randomUUID();
        presence.setStatus(id, DriverStatus.AVAILABLE, RideType.ECONOMY);
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(30));
        presence.heartbeat(id, CENTER.lat(), CENTER.lng(), null, null, Instant.now()); // arrived after the reaper's read

        assertThat(presence.evict(id, cutoff)).isFalse();
        assertThat(inGeo(RideType.ECONOMY, id)).isTrue();
    }

    @Test
    void reconcilerRebuildsTheLostProjectionOfOnlineDriversOnly() {
        TestDriver online = onlineDriver(RideType.COMFORT, CENTER);
        TestDriver offline = newDriver(RideType.ECONOMY);
        redis.execute(connection -> {
            connection.serverCommands().flushAll(); // Redis lost everything
            return null;
        }, true);

        assertThat(reconciler.reconcile()).isEqualTo(1);

        assertThat(presence.state(online.id())).get()
                .isEqualTo(new PresenceStore.State(DriverStatus.AVAILABLE, RideType.COMFORT));
        assertThat(presence.state(offline.id())).isEmpty();
        // a heartbeat from any source (e.g. tracking-service's WebSocket) now makes them searchable again
        assertThat(presence.heartbeat(online.id(), CENTER.lat(), CENTER.lng(), null, null, Instant.now()))
                .isEqualTo(HeartbeatResult.INDEXED);
        assertThat(inGeo(RideType.COMFORT, online.id())).isTrue();
        assertThat(reconciler.reconcile()).isZero();
    }
}

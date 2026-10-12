package com.rydex.driver.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.driver.Driver;
import com.rydex.driver.driver.DriverRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CandidateRankerTest {

    private static final GeoPoint PICKUP = GeoPoint.of(13.0827, 80.2707);

    private final OsrmTableClient osrm = mock(OsrmTableClient.class);
    private final DriverRepository drivers = mock(DriverRepository.class);
    private final RoutingProperties props = new RoutingProperties("http://osrm", "test", Duration.ofSeconds(1),
            Duration.ofSeconds(1), 22, 1.35);
    private CandidateRanker ranker;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ranker = new CandidateRanker(osrm, CircuitBreakerRegistry.ofDefaults(), drivers, props);
        when(drivers.findAllById(any())).thenReturn(List.of(driver(a, "4.60"), driver(b, "4.95"), driver(c, "4.80")));
    }

    private static Driver driver(UUID id, String rating) {
        Driver d = Driver.register(id, "D", RideType.ECONOMY, "TN " + id.toString().substring(0, 6), "Car", Instant.now());
        ReflectionTestUtils.setField(d, "rating", new BigDecimal(rating));
        return d;
    }

    private static Candidate candidate(UUID id, int metersNorth) {
        return new Candidate(id, GeoPoint.of(PICKUP.lat() + metersNorth / 111_195.0, PICKUP.lng()), metersNorth);
    }

    @Test
    void ordersByEtaThenRatingThenDistance() {
        // a: 4 min, b: 4 min 20 s (same minute bucket, better rating), c: 9 min
        when(osrm.durationsTo(any(), any())).thenReturn(new Double[] {240.0, 260.0, 540.0});

        var ranked = ranker.rank(PICKUP, List.of(candidate(a, 800), candidate(b, 900), candidate(c, 300)));

        assertThat(ranked).extracting(Candidate.Ranked::driverId).containsExactly(b, a, c);
        assertThat(ranked.get(0).etaS()).isEqualTo(260);
        assertThat(ranked.get(0).etaEstimated()).isFalse();
    }

    @Test
    void distanceBreaksFullTies() {
        when(drivers.findAllById(any())).thenReturn(List.of(driver(a, "4.80"), driver(b, "4.80")));
        when(osrm.durationsTo(any(), any())).thenReturn(new Double[] {300.0, 300.0});

        var ranked = ranker.rank(PICKUP, List.of(candidate(a, 1500), candidate(b, 1000)));

        assertThat(ranked).extracting(Candidate.Ranked::driverId).containsExactly(b, a);
    }

    @Test
    void fallsBackToStraightLineEtaWhenOsrmFails() {
        when(osrm.durationsTo(any(), any())).thenThrow(new IllegalStateException("down"));

        var ranked = ranker.rank(PICKUP, List.of(candidate(a, 3000), candidate(b, 1000)));

        assertThat(ranked).extracting(Candidate.Ranked::driverId).containsExactly(b, a);
        assertThat(ranked).allMatch(Candidate.Ranked::etaEstimated);
        // 1000 m x 1.35 at 22 km/h = 221 s
        assertThat(ranked.get(0).etaS()).isBetween(220, 222);
    }

    @Test
    void unreachableEntriesUseTheEstimateOnlyForThatDriver() {
        when(osrm.durationsTo(any(), any())).thenReturn(new Double[] {null, 400.0});

        var ranked = ranker.rank(PICKUP, List.of(candidate(a, 500), candidate(b, 600)));

        assertThat(ranked.get(0).driverId()).isEqualTo(a);
        assertThat(ranked.get(0).etaEstimated()).isTrue();
        assertThat(ranked.get(1).etaEstimated()).isFalse();
    }

    @Test
    void unknownDriversGetTheDefaultRating() {
        when(drivers.findAllById(any())).thenReturn(List.of());
        when(osrm.durationsTo(any(), any())).thenReturn(new Double[] {100.0});

        var ranked = ranker.rank(PICKUP, List.of(candidate(a, 500)));

        assertThat(ranked.get(0).rating()).isEqualByComparingTo("5.00");
    }

    @Test
    void noCandidatesNoCalls() {
        assertThat(ranker.rank(PICKUP, List.of())).isEmpty();
    }
}

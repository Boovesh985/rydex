package com.rydex.ride.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RideStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-12T10:45:00Z");

    @Test
    void transitionTableMatchesTheSpec() {
        assertThat(RideStatus.REQUESTED.allowedNext())
                .containsExactlyInAnyOrder(RideStatus.DRIVER_ASSIGNED, RideStatus.CANCELLED, RideStatus.NO_DRIVER_FOUND);
        assertThat(RideStatus.DRIVER_ASSIGNED.allowedNext())
                .containsExactlyInAnyOrder(RideStatus.DRIVER_ARRIVED, RideStatus.CANCELLED);
        assertThat(RideStatus.DRIVER_ARRIVED.allowedNext())
                .containsExactlyInAnyOrder(RideStatus.IN_PROGRESS, RideStatus.CANCELLED);
        assertThat(RideStatus.IN_PROGRESS.allowedNext()).containsExactly(RideStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = RideStatus.class, names = {"COMPLETED", "CANCELLED", "NO_DRIVER_FOUND"})
    void terminalStatesAllowNothing(RideStatus status) {
        assertThat(status.isTerminal()).isTrue();
        for (RideStatus next : RideStatus.values()) {
            assertThat(status.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    void happyPathSetsTimestampsAndFinalFare() {
        Ride ride = newRide();
        UUID driver = UUID.randomUUID();

        ride.assignDriver(driver, T0.plusSeconds(10));
        ride.markDriverArrived(T0.plusSeconds(300));
        ride.start(T0.plusSeconds(360));
        ride.complete(T0.plusSeconds(1200));

        assertThat(ride.getStatus()).isEqualTo(RideStatus.COMPLETED);
        assertThat(ride.getDriverId()).isEqualTo(driver);
        assertThat(ride.getAssignedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(ride.getCompletedAt()).isEqualTo(T0.plusSeconds(1200));
        assertThat(ride.getFinalFare()).isEqualByComparingTo("123");
    }

    @Test
    void cannotStartBeforeDriverArrives() {
        Ride ride = newRide();
        ride.assignDriver(UUID.randomUUID(), T0);

        assertThatThrownBy(() -> ride.start(T0))
                .isInstanceOf(IllegalRideTransitionException.class)
                .hasMessageContaining("DRIVER_ASSIGNED to IN_PROGRESS");
        assertThat(ride.getStatus()).isEqualTo(RideStatus.DRIVER_ASSIGNED);
    }

    @Test
    void cannotCancelATripInProgress() {
        Ride ride = newRide();
        ride.assignDriver(UUID.randomUUID(), T0);
        ride.markDriverArrived(T0);
        ride.start(T0);

        assertThatThrownBy(() -> ride.cancel("RIDER", "late", T0)).isInstanceOf(IllegalRideTransitionException.class);
    }

    @Test
    void cancelRecordsWhoAndWhy() {
        Ride ride = newRide();

        ride.cancel("RIDER", "Changed plans", T0);

        assertThat(ride.getStatus()).isEqualTo(RideStatus.CANCELLED);
        assertThat(ride.getCancelledBy()).isEqualTo("RIDER");
        assertThat(ride.getCancelReason()).isEqualTo("Changed plans");
    }

    static Ride newRide() {
        return Ride.request(UUID.randomUUID(), UUID.randomUUID(), RideType.ECONOMY,
                new GeoPoint(13.0827, 80.2707, "Chennai Central"), GeoPoint.of(13.05, 80.2824),
                new Quote(new BigDecimal("123.00"), 5000, 900, BigDecimal.ONE), "hash", null, T0);
    }
}

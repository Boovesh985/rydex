package com.rydex.driver.matching;

import com.rydex.common.RideType;
import com.rydex.common.geo.GeoPoint;
import com.rydex.driver.matching.MatchOffer.Outcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MatchingDtos {

    private MatchingDtos() {
    }

    /** What a driver sees in the offer modal. */
    public record OfferView(
            UUID rideId,
            RideType rideType,
            GeoPoint pickup,
            GeoPoint drop,
            BigDecimal estFare,
            int estDistanceM,
            int estDurationS,
            int pickupEtaS,
            int distanceToPickupM,
            int attempt,
            Instant offeredAt,
            Instant expiresAt,
            long secondsLeft) {

        static OfferView of(MatchOffer o, MatchJob j, Instant now) {
            long left = Math.max(0, java.time.Duration.between(now, o.getExpiresAt()).toSeconds());
            return new OfferView(j.getRideId(), j.getRideType(), j.getPickup(), j.getDrop(), j.getEstFare(),
                    j.getEstDistanceM(), j.getEstDurationS(), o.getEtaS(), o.getDistanceM(), o.getAttempt(),
                    o.getOfferedAt(), o.getExpiresAt(), left);
        }
    }

    public record AssignmentView(UUID rideId, UUID driverId, UUID riderId, GeoPoint pickup, GeoPoint drop,
                                 BigDecimal estFare, Instant assignedAt) {
    }

    /** Admin/debug view of one matching job and every offer it made. */
    public record JobView(
            UUID rideId,
            MatchJobStatus status,
            RideType rideType,
            GeoPoint pickup,
            int attempts,
            UUID currentDriverId,
            UUID assignedDriverId,
            Instant createdAt,
            Instant searchDeadline,
            List<OfferHistory> offers) {

        static JobView of(MatchJob j, List<MatchOffer> offers) {
            return new JobView(j.getRideId(), j.getStatus(), j.getRideType(), j.getPickup(), j.getAttempts(),
                    j.getCurrentDriverId(), j.getAssignedDriverId(), j.getCreatedAt(), j.getSearchDeadline(),
                    offers.stream().map(OfferHistory::of).toList());
        }
    }

    public record OfferHistory(UUID driverId, int attempt, int etaS, int distanceM, Instant offeredAt,
                               Instant respondedAt, Outcome outcome) {

        static OfferHistory of(MatchOffer o) {
            return new OfferHistory(o.getDriverId(), o.getAttempt(), o.getEtaS(), o.getDistanceM(),
                    o.getOfferedAt(), o.getRespondedAt(), o.getOutcome());
        }
    }
}

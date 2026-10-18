package com.rydex.ride.ai;

import com.rydex.common.RideType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ai-service's {@code POST /suggestions} (contracts/ai/suggestions.schema.json) and the rider's feedback. */
public final class AiDtos {

    private AiDtos() {
    }

    /**
     * @param demand  ride requests around the pickup over the last 15-30 min (the surge's input)
     * @param supply  free drivers around the pickup
     * @param routes  the quoted route first, then OSRM's alternatives
     * @param history the rider's recent completed trips, newest first
     */
    record SuggestionRequest(UUID userId, Point pickup, Point drop, Instant requestedAt, int distanceM, int durationS,
                             BigDecimal surgeMultiplier, BigDecimal maxSurge, long demand, long supply,
                             List<Option> options, List<RouteOption> routes, List<PastRide> history) {
    }

    record Point(double lat, double lng) {
    }

    /** @param baseFare the same trip without surge */
    record Option(RideType rideType, BigDecimal fare, BigDecimal baseFare, Integer etaMinutes, int driversNearby,
                  RideType upgradeTo) {
    }

    record RouteOption(int distanceM, int durationS, String polyline) {
    }

    record PastRide(RideType rideType, BigDecimal fare) {
    }

    /**
     * Shown with the estimate. {@code highlights}: SHORTER_WAIT, BETTER_PRICE, SMARTER_ROUTE.
     *
     * @param bestPickupTime    now ({@code offsetMinutes} 0) unless booking later is predicted to save money
     * @param alternativePickup a short walk to a spot with a clearly shorter wait, or null
     * @param route             the route predicted fastest in this hour's traffic
     */
    public record Suggestion(UUID suggestionId, RideType recommendedRideType, BestPickupTime bestPickupTime,
                             AlternativePickup alternativePickup, RouteChoice route, List<String> reasons,
                             List<String> highlights, double confidence, String modelVersion) {
    }

    public record BestPickupTime(int offsetMinutes, int predictedWaitMin, int predictedFare, int savingsVsNow) {
    }

    public record AlternativePickup(double lat, double lng, int walkMeters, int predictedWaitMin) {
    }

    /** @param choice "fastest" or "alternative_N"; {@code polyline} only for an alternative */
    public record RouteChoice(String choice, int predictedDurationMin, String polyline) {
    }

    /** @param accepted false: the rider dismissed the suggestion */
    public record FeedbackRequest(@NotNull Boolean accepted) {
    }
}

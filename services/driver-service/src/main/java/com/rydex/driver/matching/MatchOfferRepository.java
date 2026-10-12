package com.rydex.driver.matching;

import com.rydex.driver.matching.MatchOffer.Outcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MatchOfferRepository extends JpaRepository<MatchOffer, UUID> {

    Optional<MatchOffer> findByRideIdAndDriverIdAndOutcome(UUID rideId, UUID driverId, Outcome outcome);

    Optional<MatchOffer> findFirstByDriverIdAndOutcomeOrderByOfferedAtDesc(UUID driverId, Outcome outcome);

    List<MatchOffer> findByRideIdAndOutcome(UUID rideId, Outcome outcome);

    List<MatchOffer> findByRideIdOrderByAttempt(UUID rideId);

    @Query("select o.driverId from MatchOffer o where o.rideId = :rideId")
    List<UUID> driverIdsOffered(UUID rideId);
}

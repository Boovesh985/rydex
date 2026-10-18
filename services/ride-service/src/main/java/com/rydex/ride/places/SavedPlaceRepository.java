package com.rydex.ride.places;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SavedPlaceRepository extends JpaRepository<SavedPlace, SavedPlace.Key> {

    List<SavedPlace> findByUserIdOrderByKind(UUID userId);
}

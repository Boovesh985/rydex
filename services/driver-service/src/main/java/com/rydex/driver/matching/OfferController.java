package com.rydex.driver.matching;

import com.rydex.driver.matching.MatchingDtos.AssignmentView;
import com.rydex.driver.matching.MatchingDtos.OfferView;
import com.rydex.driver.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ride offers for the signed-in driver. Phase 4 pushes offers over WebSocket; polling
 * {@code GET /offer} keeps working as a fallback.
 */
@RestController
@RequestMapping("/api/drivers/me")
@Tag(name = "Offers", description = "Ride offers: 15 seconds to accept or reject")
class OfferController {

    private final MatchingService matching;

    OfferController(MatchingService matching) {
        this.matching = matching;
    }

    @GetMapping("/offer")
    @Operation(summary = "The ride currently offered to you (204 if none)")
    ResponseEntity<OfferView> current(@AuthenticationPrincipal Jwt jwt) {
        return matching.currentOffer(AuthUser.from(jwt))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/offers/{rideId}/accept")
    @Operation(summary = "Accept an offer: the ride is assigned to you and you go ON_TRIP")
    AssignmentView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId) {
        return matching.accept(AuthUser.from(jwt), rideId);
    }

    @PostMapping("/offers/{rideId}/reject")
    @Operation(summary = "Reject an offer: it goes to the next nearest driver")
    ResponseEntity<Void> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId) {
        matching.reject(AuthUser.from(jwt), rideId);
        return ResponseEntity.noContent().build();
    }
}

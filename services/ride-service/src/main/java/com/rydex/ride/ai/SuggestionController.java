package com.rydex.ride.ai;

import com.rydex.ride.ai.AiDtos.FeedbackRequest;
import com.rydex.ride.security.AuthUser;
import com.rydex.ride.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/suggestions")
@Tag(name = "AI suggestions")
class SuggestionController {

    private final SuggestionLog suggestionLog;

    SuggestionController(SuggestionLog suggestionLog) {
        this.suggestionLog = suggestionLog;
    }

    @PostMapping("/{id}/feedback")
    @PreAuthorize("hasRole('RIDER')")
    @Operation(summary = "Whether the rider took a suggestion (booking with its suggestionId counts as taking it)")
    ResponseEntity<Void> feedback(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody FeedbackRequest req) {
        if (!suggestionLog.feedback(id, AuthUser.from(jwt).id(), req.accepted())) {
            throw ApiException.notFound("Suggestion");
        }
        return ResponseEntity.noContent().build();
    }
}

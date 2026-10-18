package com.rydex.ride.ai;

import com.rydex.ride.ai.AiDtos.Suggestion;
import com.rydex.ride.ai.AiDtos.SuggestionRequest;
import org.springframework.web.client.RestClient;

/** Thin client for ai-service. */
public class AiClient {

    private final RestClient http;

    public AiClient(RestClient http) {
        this.http = http;
    }

    Suggestion suggest(SuggestionRequest request) {
        return http.post().uri("/suggestions").body(request).retrieve().body(Suggestion.class);
    }
}

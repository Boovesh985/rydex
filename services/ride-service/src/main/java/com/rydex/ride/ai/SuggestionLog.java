package com.rydex.ride.ai;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ai_suggestion_log}: every suggestion shown, and whether the rider booked with it (the
 * feedback for retraining and the acceptance rate).
 */
@Component
public class SuggestionLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    SuggestionLog(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    void shown(UUID userId, Object request, AiDtos.Suggestion suggestion) {
        jdbc.sql("""
                        INSERT INTO ai_suggestion_log (id, user_id, request, response, model_version, created_at)
                        VALUES (:id, :user, CAST(:request AS jsonb), CAST(:response AS jsonb), :version, :at)
                        """)
                .param("id", suggestion.suggestionId())
                .param("user", userId)
                .param("request", json.writeValueAsString(request))
                .param("response", json.writeValueAsString(suggestion))
                .param("version", suggestion.modelVersion())
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    /** Whether this rider was shown the suggestion. */
    public boolean ownedBy(UUID suggestionId, UUID userId) {
        return suggestionId != null && jdbc.sql("SELECT count(*) FROM ai_suggestion_log WHERE id = :id AND user_id = :user")
                .param("id", suggestionId).param("user", userId).query(Long.class).single() > 0;
    }

    /** The rider booked with it; runs in the booking's transaction. */
    @Transactional
    public void booked(UUID suggestionId, UUID userId, UUID rideId) {
        jdbc.sql("""
                        UPDATE ai_suggestion_log SET accepted = true, ride_id = :ride, decided_at = :at
                        WHERE id = :id AND user_id = :user
                        """)
                .param("ride", rideId).param("at", Timestamp.from(clock.instant()))
                .param("id", suggestionId).param("user", userId)
                .update();
    }

    /**
     * The rider's own verdict. Once they have booked with the suggestion, that stands.
     *
     * @return false when this rider was never shown the suggestion
     */
    @Transactional
    public boolean feedback(UUID suggestionId, UUID userId, boolean accepted) {
        if (!ownedBy(suggestionId, userId)) {
            return false;
        }
        jdbc.sql("""
                        UPDATE ai_suggestion_log SET accepted = :accepted, decided_at = :at
                        WHERE id = :id AND user_id = :user AND ride_id IS NULL
                        """)
                .param("accepted", accepted).param("at", Timestamp.from(clock.instant()))
                .param("id", suggestionId).param("user", userId)
                .update();
        return true;
    }
}

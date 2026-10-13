package com.rydex.driver.matching;

import com.rydex.common.ws.WsDestinations;
import com.rydex.common.ws.WsPush;
import com.rydex.driver.matching.MatchingDtos.OfferView;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pushes offers to the driver's WebSocket ({@code /user/queue/offers}) through tracking-service's
 * Redis channel (ADR 0011). Offers last 15 s, so this is fire-and-forget: a driver who misses the
 * push still sees the offer with {@code GET /api/drivers/me/offer}. Called after commit; a failure
 * is logged, never thrown, so it cannot undo a committed offer.
 */
@Component
public class OfferNotifier {

    /** Why an offer stopped being open. */
    public enum Closed {
        ACCEPTED,
        REJECTED,
        EXPIRED,
        CANCELLED
    }

    /**
     * What a driver receives on {@code /user/queue/offers}.
     *
     * @param type   {@code OFFER} (a new offer, with {@code offer}) or {@code OFFER_CLOSED} (with {@code reason})
     * @param reason why it closed, for {@code OFFER_CLOSED}
     */
    public record OfferMessage(String type, UUID rideId, Closed reason, OfferView offer) {
    }

    private static final Logger log = LoggerFactory.getLogger(OfferNotifier.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public OfferNotifier(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void offered(UUID driverId, OfferView offer) {
        push(driverId, new OfferMessage("OFFER", offer.rideId(), null, offer));
    }

    public void closed(UUID driverId, UUID rideId, Closed reason) {
        push(driverId, new OfferMessage("OFFER_CLOSED", rideId, reason, null));
    }

    private void push(UUID driverId, OfferMessage message) {
        try {
            String body = json.writeValueAsString(WsPush.toUser(driverId, WsDestinations.OFFERS, message));
            redis.convertAndSend(WsPush.CHANNEL, body);
        } catch (RuntimeException e) {
            log.warn("Could not push {} for ride {} to driver {}: {}", message.type(), message.rideId(), driverId, e.toString());
        }
    }
}

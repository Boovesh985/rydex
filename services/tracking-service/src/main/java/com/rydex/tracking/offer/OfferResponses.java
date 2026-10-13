package com.rydex.tracking.offer;

import com.rydex.common.ws.WsDestinations;
import com.rydex.tracking.security.AuthUser;
import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP {@code /app/offer.respond}: a driver accepts or rejects the ride offered to them.
 * driver-service owns matching, so the answer is forwarded to its REST API with the driver's own
 * token ({@code POST /api/drivers/me/offers/{rideId}/accept|reject}) and the outcome is sent back
 * on {@code /user/queue/offers}.
 */
@Controller
class OfferResponses {

    /** What the driver sends. */
    record OfferAnswer(UUID rideId, Boolean accept) {
    }

    /**
     * What the driver gets back on {@code /user/queue/offers}.
     *
     * @param type       always {@code OFFER_RESPONSE}
     * @param ok         whether driver-service took the answer
     * @param status     driver-service's HTTP status (e.g. 409 when the offer expired meanwhile)
     * @param detail     why it failed, from driver-service's problem details
     * @param assignment on a successful accept: the ride, rider and pickup now assigned to the driver
     */
    record OfferResponseResult(String type, UUID rideId, boolean accept, boolean ok, int status, String detail,
                               JsonNode assignment) {
    }

    private static final Logger log = LoggerFactory.getLogger(OfferResponses.class);

    private final RestClient driverService;
    private final SimpMessagingTemplate messaging;
    private final JsonMapper json;

    OfferResponses(@Qualifier("driverServiceRestClient") RestClient driverService, SimpMessagingTemplate messaging,
                   JsonMapper json) {
        this.driverService = driverService;
        this.messaging = messaging;
        this.json = json;
    }

    @MessageMapping("offer.respond")
    void respond(@Payload OfferAnswer answer, Principal principal) {
        AuthUser driver = (AuthUser) principal;
        OfferResponseResult result = answer.rideId() == null || answer.accept() == null
                ? new OfferResponseResult("OFFER_RESPONSE", answer.rideId(), Boolean.TRUE.equals(answer.accept()), false,
                        400, "rideId and accept are required", null)
                : forward(driver, answer.rideId(), answer.accept());
        messaging.convertAndSendToUser(driver.getName(), WsDestinations.OFFERS, result);
    }

    private OfferResponseResult forward(AuthUser driver, UUID rideId, boolean accept) {
        try {
            return driverService.post()
                    .uri("/api/drivers/me/offers/{rideId}/{action}", rideId, accept ? "accept" : "reject")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + driver.token())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] bytes = response.getBody().readAllBytes();
                        JsonNode body = bytes.length == 0 ? null : json.readTree(bytes);
                        boolean ok = response.getStatusCode().is2xxSuccessful();
                        String detail = ok || body == null ? null : body.path("detail").asString(null);
                        return new OfferResponseResult("OFFER_RESPONSE", rideId, accept, ok, status, detail,
                                ok && accept ? body : null);
                    });
        } catch (RuntimeException e) {
            log.warn("Could not reach driver-service for ride {}: {}", rideId, e.toString());
            return new OfferResponseResult("OFFER_RESPONSE", rideId, accept, false, 503,
                    "Matching is unavailable right now; try again", null);
        }
    }
}

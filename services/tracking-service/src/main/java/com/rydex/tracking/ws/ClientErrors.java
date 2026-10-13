package com.rydex.tracking.ws;

import com.rydex.common.ws.WsDestinations;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * Tells a client that something it sent was not accepted, on {@code /user/queue/errors}. The
 * client is connected to this instance (it just sent the frame), so this goes straight to the
 * local broker instead of through Redis.
 */
@Component
public class ClientErrors {

    /** What a client receives on {@code /user/queue/errors}. */
    public record ClientError(String code, String message, Instant at) {
    }

    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    ClientErrors(SimpMessagingTemplate messaging, Clock clock) {
        this.messaging = messaging;
        this.clock = clock;
    }

    public void send(Principal user, String code, String message) {
        messaging.convertAndSendToUser(user.getName(), WsDestinations.ERRORS, new ClientError(code, message, clock.instant()));
    }

    /** Unreadable payloads and unexpected failures in {@code @MessageMapping} methods. */
    @ControllerAdvice
    static class Handler {

        private final ClientErrors errors;

        Handler(ClientErrors errors) {
            this.errors = errors;
        }

        @MessageExceptionHandler(MessageConversionException.class)
        void unreadable(MessageConversionException e, Principal user) {
            errors.send(user, "BAD_MESSAGE", "The message body is not valid JSON for this destination");
        }

        @MessageExceptionHandler(RuntimeException.class)
        void failed(RuntimeException e, Principal user) {
            errors.send(user, "FAILED", "The message could not be processed; try again");
        }
    }
}

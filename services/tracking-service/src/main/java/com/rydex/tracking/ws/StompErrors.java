package com.rydex.tracking.ws;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * The text of the STOMP ERROR frame sent when a frame is refused (the connection is closed after
 * it). Spring would report the channel's wrapper exception ("Failed to send message to
 * ExecutorSubscribableChannel..."); clients get the reason from {@link StompAuthInterceptor}
 * instead, and nothing about other failures.
 */
class StompErrors extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        Throwable cause = ex instanceof MessageDeliveryException && ex.getCause() != null ? ex.getCause() : ex;
        boolean refused = cause instanceof AuthenticationException || cause instanceof AccessDeniedException;
        return super.handleClientMessageProcessingError(clientMessage,
                refused ? cause : new IllegalStateException("The frame could not be processed"));
    }
}

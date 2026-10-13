package com.rydex.tracking.ws;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** {@code ws_active_sessions}: STOMP sessions open on this instance (master prompt §14). */
@Component
class WsSessionMetrics {

    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    WsSessionMetrics(MeterRegistry registry) {
        Gauge.builder("ws_active_sessions", sessions, Set::size)
                .description("STOMP sessions currently connected to this instance")
                .register(registry);
    }

    @EventListener
    void connected(SessionConnectedEvent event) {
        String id = (String) event.getMessage().getHeaders().get("simpSessionId");
        if (id != null) {
            sessions.add(id);
        }
    }

    @EventListener
    void disconnected(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }

    int activeSessions() {
        return sessions.size();
    }
}

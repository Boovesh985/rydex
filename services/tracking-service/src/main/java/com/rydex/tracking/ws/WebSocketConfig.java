package com.rydex.tracking.ws;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over plain WebSocket at {@code /ws} (no SockJS: every client we target, browsers and k6,
 * speaks WebSocket). An in-memory broker per instance handles {@code /topic} and {@code /queue};
 * {@link PushRelay} connects the instances through Redis (ADR 0011).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor auth;
    private final List<String> allowedOrigins;
    private final Duration heartbeat;
    private final ThreadPoolTaskScheduler heartbeatScheduler;

    WebSocketConfig(StompAuthInterceptor auth, @Value("${rydex.cors.allowed-origins}") List<String> allowedOrigins,
                    @Value("${rydex.ws.heartbeat}") Duration heartbeat, ThreadPoolTaskScheduler wsHeartbeatScheduler) {
        this.auth = auth;
        this.allowedOrigins = allowedOrigins;
        this.heartbeat = heartbeat;
        this.heartbeatScheduler = wsHeartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // browsers send Origin on the handshake; native clients (k6, the simulator) do not and are allowed
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
        registry.setErrorHandler(new StompErrors());
        // Not setPreserveReceiveOrder(true): its ordering decorator swallows exceptions from
        // StompAuthInterceptor, so a refused SUBSCRIBE/SEND would get no ERROR frame. Location
        // updates are at least 500 ms apart (rate limit), so they cannot overtake each other anyway.
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {heartbeat.toMillis(), heartbeat.toMillis()})
                .setTaskScheduler(heartbeatScheduler);
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(auth);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // our frames are small; a slow client is dropped rather than buffering without bound
        registration.setMessageSizeLimit(16 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(10_000);
    }

    @Configuration(proxyBeanMethods = false)
    static class SchedulerConfig {

        @Bean
        ThreadPoolTaskScheduler wsHeartbeatScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("ws-heartbeat-");
            scheduler.setDaemon(true);
            return scheduler;
        }
    }
}

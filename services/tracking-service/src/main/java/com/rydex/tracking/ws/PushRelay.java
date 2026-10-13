package com.rydex.tracking.ws;

import com.rydex.common.ws.WsPush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fans pushes out to every tracking-service instance (ADR 0011). A push is published on the Redis
 * channel {@link WsPush#CHANNEL}; each instance receives it and hands it to its in-memory STOMP
 * broker, which delivers it to whichever matching sessions that instance holds. So a rider
 * connected to instance A sees the location their driver sent to instance B. driver-service
 * publishes ride offers on the same channel.
 *
 * <p>Fire-and-forget, like the WebSocket itself: a client that was disconnected re-reads state
 * over REST when it reconnects.
 */
@Component
public class PushRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PushRelay.class);

    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messaging;
    private final JsonMapper json;

    PushRelay(StringRedisTemplate redis, SimpMessagingTemplate messaging, JsonMapper json) {
        this.redis = redis;
        this.messaging = messaging;
        this.json = json;
    }

    /** Sends {@code push} to its recipients, wherever they are connected. Never throws. */
    public void publish(WsPush push) {
        try {
            redis.convertAndSend(WsPush.CHANNEL, json.writeValueAsString(push));
        } catch (RuntimeException e) {
            log.warn("Could not publish a push to {}: {}", push.destination(), e.toString());
        }
    }

    /** A push from any instance or service: deliver it to the local sessions it targets. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode push = json.readTree(message.getBody());
            String destination = push.path("destination").asString();
            JsonNode payload = push.path("payload");
            JsonNode user = push.path("user");
            if (user.isString()) {
                messaging.convertAndSendToUser(user.asString(), destination, payload);
            } else {
                messaging.convertAndSend(destination, payload);
            }
        } catch (RuntimeException e) {
            log.warn("Dropped an undeliverable push: {}", e.toString());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerConfig {

        @Bean
        RedisMessageListenerContainer pushListenerContainer(RedisConnectionFactory connections, PushRelay relay) {
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connections);
            container.addMessageListener(relay, new ChannelTopic(WsPush.CHANNEL));
            return container;
        }
    }
}

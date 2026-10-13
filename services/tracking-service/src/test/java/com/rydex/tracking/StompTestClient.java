package com.rydex.tracking;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

/**
 * A STOMP client for tests, like the browser or the simulator would be: connects to {@code /ws}
 * with a bearer token, subscribes, sends, and records what the server pushed or how it refused.
 */
public final class StompTestClient implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final StompSession session;
    private final Handler handler;

    private StompTestClient(StompSession session, Handler handler) {
        this.session = session;
        this.handler = handler;
    }

    /** Connects or throws (e.g. when the server refuses the token). */
    public static StompTestClient connect(int port, String token) {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        client.setDefaultHeartbeat(new long[] {0, 0});
        var connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        var handler = new Handler();
        CompletableFuture<StompSession> future = client.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), connectHeaders, handler);
        try {
            return new StompTestClient(future.get(WAIT.toSeconds(), TimeUnit.SECONDS), handler);
        } catch (Exception e) {
            throw new IllegalStateException("STOMP connect failed: " + handler.errors.peek(), e);
        }
    }

    /**
     * Subscribes and returns the queue the destination's messages land in (as JSON). Waits a moment
     * afterwards: the broker registers a subscription asynchronously, and a message published
     * before that would not be delivered.
     */
    public BlockingQueue<JsonNode> subscribe(String destination) {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((JsonNode) payload);
            }
        });
        settle();
        return messages;
    }

    private static void settle() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void send(String destination, Map<String, ?> payload) {
        session.send(destination, payload);
    }

    /** The next message, or fails after a few seconds. */
    public static JsonNode next(BlockingQueue<JsonNode> queue) {
        try {
            JsonNode message = queue.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
            if (message == null) {
                throw new AssertionError("no message within " + WAIT);
            }
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** True if nothing arrives within {@code window}. */
    public static boolean quiet(BlockingQueue<JsonNode> queue, Duration window) {
        try {
            return queue.poll(window.toMillis(), TimeUnit.MILLISECONDS) == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Waits until the server has closed the connection (e.g. after an ERROR frame); returns its message. */
    public String awaitRefusal() {
        try {
            String error = handler.errors.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
            if (error == null) {
                throw new AssertionError("the server did not refuse within " + WAIT);
            }
            return error;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    @Override
    public void close() {
        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (RuntimeException e) {
            // the server hung up first (e.g. after an ERROR frame)
        }
    }

    private static final class Handler extends StompSessionHandlerAdapter {

        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            // ERROR frames from the server land here
            errors.add("ERROR " + headers.getFirst("message"));
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload,
                                    Throwable exception) {
            errors.add("EXCEPTION " + exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add("CLOSED " + exception);
        }
    }
}

package com.rydex.ride;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for ai-service's {@code POST /suggestions} on a local port, shared by every test class.
 * It answers 503 unless a test tells it otherwise, so estimates elsewhere carry no suggestion.
 */
public final class AiStub {

    private static final HttpServer SERVER;
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(503);
    private static final AtomicInteger DELAY_MS = new AtomicInteger();
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        SERVER.createContext("/suggestions", exchange -> {
            CALLS.incrementAndGet();
            LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(DELAY_MS.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = (BODY.get() == null ? "{}" : BODY.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(STATUS.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // the client gave up waiting
            }
            exchange.close();
        });
        SERVER.start();
    }

    private AiStub() {
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /** Answer every call with this JSON, after {@code delayMs}. */
    public static void answer(String json, int delayMs) {
        BODY.set(json);
        DELAY_MS.set(delayMs);
        STATUS.set(200);
    }

    /** Back to failing every call (the default). */
    public static void down() {
        BODY.set(null);
        DELAY_MS.set(0);
        STATUS.set(503);
    }

    public static String lastRequest() {
        return LAST_REQUEST.get();
    }

    public static int calls() {
        return CALLS.get();
    }
}

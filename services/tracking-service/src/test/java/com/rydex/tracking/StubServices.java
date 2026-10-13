package com.rydex.tracking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal stand-ins for ride-service and driver-service, the two services tracking-service calls on
 * a user's behalf. Started once per JVM on a free port.
 * <ul>
 *   <li>{@code GET /api/rides/{id}}: 200 for a (ride, token) pair registered with {@link #allowRide}, else 404.</li>
 *   <li>{@code POST /api/drivers/me/offers/{id}/accept|reject}: the response set with {@link #answerOffer}
 *       (default 200 with an assignment for accept, 204 for reject).</li>
 * </ul>
 */
public final class StubServices {

    /** A request the stubs received. */
    public record Received(String method, String path, String authorization) {
    }

    private record Canned(int status, String body) {
    }

    private static final Map<String, String> ALLOWED_RIDES = new ConcurrentHashMap<>();
    private static final Map<UUID, Canned> OFFER_ANSWERS = new ConcurrentHashMap<>();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = start();

    private StubServices() {
    }

    public static String url() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    public static void allowRide(UUID rideId, String token) {
        ALLOWED_RIDES.put(rideId.toString(), token);
    }

    public static void answerOffer(UUID rideId, int status, String body) {
        OFFER_ANSWERS.put(rideId, new Canned(status, body));
    }

    public static List<Received> received() {
        return List.copyOf(RECEIVED);
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", StubServices::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        RECEIVED.add(new Received(ex.getRequestMethod(), path, auth));
        String[] parts = path.split("/");
        if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/api/rides/") && parts.length == 4) {
            String token = ALLOWED_RIDES.get(parts[3]);
            boolean allowed = token != null && ("Bearer " + token).equals(auth);
            respond(ex, allowed ? 200 : 404, allowed ? "{\"id\":\"" + parts[3] + "\"}" : problem(404, "Ride not found"));
            return;
        }
        if ("POST".equals(ex.getRequestMethod()) && path.startsWith("/api/drivers/me/offers/") && parts.length == 7) {
            UUID rideId = UUID.fromString(parts[5]);
            boolean accept = "accept".equals(parts[6]);
            Canned canned = OFFER_ANSWERS.getOrDefault(rideId, accept
                    ? new Canned(200, "{\"rideId\":\"" + rideId + "\",\"driverId\":\"" + UUID.randomUUID() + "\"}")
                    : new Canned(204, ""));
            respond(ex, canned.status(), canned.body());
            return;
        }
        respond(ex, 404, problem(404, "No stub for " + path));
    }

    public static String problem(int status, String detail) {
        return "{\"status\":" + status + ",\"title\":\"Problem\",\"detail\":\"" + detail + "\"}";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            ex.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}

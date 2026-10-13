package com.rydex.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-memory stand-in for the Razorpay API (orders, payments, capture, refunds), started once per
 * JVM. Tests play the rider's part with {@link #pay}: Checkout itself only exists in a browser.
 */
public final class RazorpayStub {

    public static final String KEY_ID = "rzp_test_stubkey";
    public static final String KEY_SECRET = "stub_secret";
    public static final String WEBHOOK_SECRET = "stub_webhook_secret";

    /** A request the stub received. */
    public record Received(String method, String path, JsonNode body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));

    private static final Map<String, Map<String, Object>> ORDERS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> PAYMENTS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> REFUNDS = new LinkedHashMap<>();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicLong IDS = new AtomicLong(1000);
    private static final AtomicInteger UNAVAILABLE = new AtomicInteger();
    private static final AtomicInteger LOSE_REFUND_RESPONSES = new AtomicInteger();
    private static volatile String refundStatus = "processed";
    private static final HttpServer SERVER = start();

    private RazorpayStub() {
    }

    public static String baseUrl() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/v1";
    }

    public static synchronized void reset() {
        ORDERS.clear();
        PAYMENTS.clear();
        REFUNDS.clear();
        RECEIVED.clear();
        UNAVAILABLE.set(0);
        LOSE_REFUND_RESPONSES.set(0);
        refundStatus = "processed";
    }

    /** The rider makes a checkout attempt on {@code orderId}; returns the Razorpay payment id. */
    public static synchronized String pay(String orderId, String status, String errorDescription) {
        Map<String, Object> order = ORDERS.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("no such order " + orderId);
        }
        String id = "pay_" + IDS.incrementAndGet();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", id);
        payment.put("entity", "payment");
        payment.put("order_id", orderId);
        payment.put("amount", order.get("amount"));
        payment.put("currency", order.get("currency"));
        payment.put("status", status);
        payment.put("error_description", errorDescription);
        payment.put("created_at", IDS.get());
        PAYMENTS.put(id, payment);
        if ("captured".equals(status)) {
            order.put("status", "paid");
        }
        return id;
    }

    /** The next {@code n} calls answer 503. */
    public static void unavailableFor(int n) {
        UNAVAILABLE.set(n);
    }

    /** New refunds start in this status ("processed" by default, or "pending"). */
    public static void refundStatus(String status) {
        refundStatus = status;
    }

    /** The next refund is created, but the caller gets a 503 instead of the answer. */
    public static void loseNextRefundResponse() {
        LOSE_REFUND_RESPONSES.set(1);
    }

    public static synchronized void settleRefund(String refundId, String status) {
        REFUNDS.get(refundId).put("status", status);
    }

    public static synchronized List<Map<String, Object>> refunds() {
        return new ArrayList<>(REFUNDS.values());
    }

    public static synchronized Map<String, Object> order(String orderId) {
        return ORDERS.get(orderId);
    }

    public static List<Received> received() {
        return List.copyOf(RECEIVED);
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/", RazorpayStub::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().substring("/v1".length());
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = raw.isBlank() ? null : JSON.readTree(raw);
            RECEIVED.add(new Received(method, path, body));
            if (!AUTH.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                send(ex, 401, error("Authentication failed"));
                return;
            }
            if (UNAVAILABLE.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                send(ex, 503, error("Service unavailable"));
                return;
            }
            synchronized (RazorpayStub.class) {
                route(ex, method, path.split("/"), body);
            }
        }
    }

    /** {@code parts} of "/payments/pay_1/refund" are ["", "payments", "pay_1", "refund"]. */
    private static void route(HttpExchange ex, String method, String[] parts, JsonNode body) throws IOException {
        if ("POST".equals(method) && parts.length == 2 && "orders".equals(parts[1])) {
            String id = "order_" + IDS.incrementAndGet();
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("id", id);
            order.put("entity", "order");
            order.put("amount", body.path("amount").asLong());
            order.put("currency", body.path("currency").asString());
            order.put("receipt", body.path("receipt").asString());
            order.put("status", "created");
            ORDERS.put(id, order);
            send(ex, 200, order);
        } else if ("GET".equals(method) && parts.length == 4 && "orders".equals(parts[1]) && "payments".equals(parts[3])) {
            List<Map<String, Object>> items = PAYMENTS.values().stream()
                    .filter(p -> parts[2].equals(p.get("order_id"))).toList();
            send(ex, 200, Map.of("entity", "collection", "count", items.size(), "items", items));
        } else if ("GET".equals(method) && parts.length == 3 && "payments".equals(parts[1])) {
            Map<String, Object> payment = PAYMENTS.get(parts[2]);
            send(ex, payment == null ? 400 : 200, payment == null ? error("The id provided does not exist") : payment);
        } else if ("POST".equals(method) && parts.length == 4 && "payments".equals(parts[1]) && "capture".equals(parts[3])) {
            Map<String, Object> payment = PAYMENTS.get(parts[2]);
            if (payment == null || !"authorized".equals(payment.get("status"))) {
                send(ex, 400, error("This payment has already been captured"));
                return;
            }
            payment.put("status", "captured");
            ORDERS.get((String) payment.get("order_id")).put("status", "paid");
            send(ex, 200, payment);
        } else if ("POST".equals(method) && parts.length == 4 && "payments".equals(parts[1]) && "refund".equals(parts[3])) {
            Map<String, Object> payment = PAYMENTS.get(parts[2]);
            if (payment == null || !"captured".equals(payment.get("status"))) {
                send(ex, 400, error("The payment has not been captured"));
                return;
            }
            String id = "rfnd_" + IDS.incrementAndGet();
            Map<String, Object> refund = new LinkedHashMap<>();
            refund.put("id", id);
            refund.put("entity", "refund");
            refund.put("payment_id", parts[2]);
            refund.put("amount", body.path("amount").asLong());
            refund.put("status", refundStatus);
            refund.put("notes", Map.of("refund_id", body.path("notes").path("refund_id").asString()));
            REFUNDS.put(id, refund);
            if (LOSE_REFUND_RESPONSES.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                send(ex, 503, error("Gateway timeout"));
                return;
            }
            send(ex, 200, refund);
        } else if ("GET".equals(method) && parts.length == 4 && "payments".equals(parts[1]) && "refunds".equals(parts[3])) {
            List<Map<String, Object>> items = REFUNDS.values().stream()
                    .filter(r -> parts[2].equals(r.get("payment_id"))).toList();
            send(ex, 200, Map.of("entity", "collection", "count", items.size(), "items", items));
        } else if ("GET".equals(method) && parts.length == 3 && "refunds".equals(parts[1])) {
            Map<String, Object> refund = REFUNDS.get(parts[2]);
            send(ex, refund == null ? 400 : 200, refund == null ? error("The id provided does not exist") : refund);
        } else {
            send(ex, 404, error("No such endpoint"));
        }
    }

    private static Map<String, Object> error(String description) {
        return Map.of("error", Map.of("code", "BAD_REQUEST_ERROR", "description", description));
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}

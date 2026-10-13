package com.rydex.payment.razorpay;

import com.rydex.payment.provider.ProviderUnavailableException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The few Razorpay REST calls Rydex needs (https://razorpay.com/docs/api/), authenticated with the
 * key id and secret (HTTP basic). Amounts are in paise. Network errors, 5xx and 401 (bad keys:
 * an operator must fix them, then it works) throw {@link ProviderUnavailableException}; other 4xx
 * throw {@link RazorpayApiException}, whose description Razorpay meant for humans.
 */
public class RazorpayClient {

    /** An order: what the rider pays in checkout. */
    public record Order(String id, long amount, String status) {
    }

    /** One checkout attempt: created, authorized, captured, refunded or failed. */
    public record Payment(String id, String orderId, long amount, String status, String errorDescription,
                          long createdAt) {
    }

    /** {@code refundId} is Rydex's refund id, from the notes it was created with. */
    public record RefundEntity(String id, String paymentId, long amount, String status, String refundId) {
    }

    public static class RazorpayApiException extends RuntimeException {

        private final int status;

        RazorpayApiException(int status, String description) {
            super(description);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final RestClient http;
    private final JsonMapper json;

    public RazorpayClient(RestClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    public Order createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes) {
        JsonNode n = call(HttpMethod.POST, "/orders",
                Map.of("amount", amountPaise, "currency", currency, "receipt", receipt, "notes", notes));
        return new Order(n.path("id").asString(), n.path("amount").asLong(), n.path("status").asString());
    }

    public List<Payment> orderPayments(String orderId) {
        List<Payment> payments = new ArrayList<>();
        call(HttpMethod.GET, "/orders/" + orderId + "/payments", null).path("items")
                .forEach(item -> payments.add(payment(item)));
        return payments;
    }

    public Payment payment(String paymentId) {
        return payment(call(HttpMethod.GET, "/payments/" + paymentId, null));
    }

    public Payment capture(String paymentId, long amountPaise, String currency) {
        return payment(call(HttpMethod.POST, "/payments/" + paymentId + "/capture",
                Map.of("amount", amountPaise, "currency", currency)));
    }

    public RefundEntity refund(String paymentId, long amountPaise, String receipt, Map<String, String> notes) {
        return refund(call(HttpMethod.POST, "/payments/" + paymentId + "/refund",
                Map.of("amount", amountPaise, "speed", "normal", "receipt", receipt, "notes", notes)));
    }

    public List<RefundEntity> refunds(String paymentId) {
        List<RefundEntity> refunds = new ArrayList<>();
        call(HttpMethod.GET, "/payments/" + paymentId + "/refunds", null).path("items")
                .forEach(item -> refunds.add(refund(item)));
        return refunds;
    }

    public RefundEntity refundById(String refundId) {
        return refund(call(HttpMethod.GET, "/refunds/" + refundId, null));
    }

    private static Payment payment(JsonNode n) {
        return new Payment(n.path("id").asString(), text(n.path("order_id")), n.path("amount").asLong(),
                n.path("status").asString(), text(n.path("error_description")), n.path("created_at").asLong());
    }

    private static RefundEntity refund(JsonNode n) {
        return new RefundEntity(n.path("id").asString(), text(n.path("payment_id")), n.path("amount").asLong(),
                n.path("status").asString(), text(n.path("notes").path("refund_id")));
    }

    private static String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    private JsonNode call(HttpMethod method, String path, Object body) {
        try {
            var spec = http.method(method).uri(path);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
            }
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                if (status >= 200 && status < 300) {
                    return json.readTree(text);
                }
                String description = describe(text);
                if (status == 401) {
                    throw new ProviderUnavailableException("Razorpay rejected the API keys: " + description);
                }
                if (status >= 500 || status == 429) {
                    throw new ProviderUnavailableException("Razorpay answered " + status + ": " + description);
                }
                throw new RazorpayApiException(status, description);
            });
        } catch (ResourceAccessException | UncheckedIOException e) {
            throw new ProviderUnavailableException("Razorpay is unreachable: " + e.getMessage(), e);
        }
    }

    /** Razorpay errors look like {"error": {"code": "BAD_REQUEST_ERROR", "description": "..."}}. */
    private String describe(String body) {
        try {
            String description = json.readTree(body).path("error").path("description").asString();
            return description.isBlank() ? abbreviate(body) : description;
        } catch (RuntimeException e) {
            return abbreviate(body);
        }
    }

    private static String abbreviate(String body) {
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}

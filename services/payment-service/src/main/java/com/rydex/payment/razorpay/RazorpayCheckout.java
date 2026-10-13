package com.rydex.payment.razorpay;

import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import com.rydex.payment.config.PaymentProperties;
import com.rydex.payment.payment.Payment;
import com.rydex.payment.payment.PaymentDtos.PaymentView;
import com.rydex.payment.payment.PaymentDtos.VerifyRequest;
import com.rydex.payment.payment.PaymentLedger;
import com.rydex.payment.payment.PaymentProcessor;
import com.rydex.payment.payment.PaymentRepository;
import com.rydex.payment.payment.PaymentService;
import com.rydex.payment.payment.Refund;
import com.rydex.payment.payment.RefundRepository;
import com.rydex.payment.provider.PaymentProvider.ChargeOutcome;
import com.rydex.payment.provider.PaymentProvider.Charged;
import com.rydex.payment.provider.PaymentProvider.Declined;
import com.rydex.payment.provider.PaymentProvider.Processed;
import com.rydex.payment.provider.PaymentProvider.Rejected;
import com.rydex.payment.provider.ProviderUnavailableException;
import com.rydex.payment.razorpay.RazorpayClient.RazorpayApiException;
import com.rydex.payment.security.AuthUser;
import com.rydex.payment.web.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two ways Razorpay tells Rydex a rider paid, besides the processor asking: the checkout
 * callback the rider's browser forwards (signed with the key secret), and webhooks (signed with
 * the webhook secret). Both only record what Razorpay confirms, and recording is idempotent, so
 * it does not matter which arrives first.
 */
@Service
public class RazorpayCheckout {

    private static final Logger log = LoggerFactory.getLogger(RazorpayCheckout.class);

    private final ObjectProvider<RazorpayProvider> razorpay;
    private final PaymentService paymentService;
    private final PaymentLedger ledger;
    private final PaymentProcessor processor;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentProperties.Razorpay props;
    private final JsonMapper json;

    RazorpayCheckout(ObjectProvider<RazorpayProvider> razorpay, PaymentService paymentService, PaymentLedger ledger,
                     PaymentProcessor processor, PaymentRepository payments, RefundRepository refunds,
                     PaymentProperties props, JsonMapper json) {
        this.razorpay = razorpay;
        this.paymentService = paymentService;
        this.ledger = ledger;
        this.processor = processor;
        this.payments = payments;
        this.refunds = refunds;
        this.props = props.razorpay();
        this.json = json;
    }

    /** Checkout's success handler, forwarded by the rider's app. */
    public PaymentView verify(AuthUser rider, UUID paymentId, VerifyRequest req) {
        RazorpayProvider provider = razorpay.getIfAvailable();
        if (provider == null) {
            throw ApiException.conflict("Razorpay is not configured on this server");
        }
        Payment p = paymentService.awaitingRazorpay(rider, paymentId);
        if (!req.razorpayOrderId().equals(p.getProviderRef())) {
            throw ApiException.badRequest("This checkout was for another order");
        }
        if (!RazorpaySignatures.checkoutValid(req.razorpayOrderId(), req.razorpayPaymentId(), req.razorpaySignature(),
                props.keySecret())) {
            throw ApiException.badRequest("Invalid payment signature");
        }
        if (p.getStatus() == Status.SUCCEEDED || p.getStatus() == Status.REFUNDED) {
            return paymentService.view(paymentId, rider);
        }
        ChargeOutcome outcome;
        try {
            outcome = provider.confirm(p, req.razorpayPaymentId());
        } catch (ProviderUnavailableException e) {
            ledger.checkSoon(paymentId);
            throw ApiException.badGateway("Razorpay could not be reached to confirm the payment; Rydex will keep "
                    + "checking and confirm it automatically");
        } catch (IllegalArgumentException | RazorpayApiException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        record(paymentId, outcome);
        return paymentService.view(paymentId, rider);
    }

    /**
     * A webhook call. Unknown orders and refunds are ignored (and acknowledged), so Razorpay does
     * not keep retrying events for payments that are not Rydex's.
     */
    public void webhook(String body, String signature) {
        if (!props.webhookEnabled()) {
            throw ApiException.notFound("Webhook");
        }
        if (!RazorpaySignatures.webhookValid(body, signature, props.webhookSecret())) {
            throw ApiException.badRequest("Invalid webhook signature");
        }
        JsonNode root = json.readTree(body);
        String event = root.path("event").asString();
        JsonNode payment = root.path("payload").path("payment").path("entity");
        JsonNode refund = root.path("payload").path("refund").path("entity");
        switch (event) {
            case "payment.captured", "order.paid" -> byOrder(payment).ifPresent(p -> {
                if (payment.path("amount").asLong() != RazorpayProvider.paise(p.getAmount())) {
                    log.warn("Ignoring {} for payment {}: amount {} paise does not match", event, p.getId(),
                            payment.path("amount").asLong());
                    return;
                }
                record(p.getId(), new Charged(p.getProviderRef(), payment.path("id").asString()));
            });
            case "payment.authorized" -> byOrder(payment).ifPresent(p -> {
                ledger.checkSoon(p.getId()); // the processor captures it
                processor.kick(p.getId());
            });
            case "payment.failed" -> byOrder(payment).ifPresent(p -> record(p.getId(), new Declined(p.getProviderRef(),
                    payment.path("error_description").asString("Payment failed"))));
            case "refund.processed" -> byRefund(refund).ifPresent(r ->
                    ledger.recordRefund(r.getId(), new Processed(refund.path("id").asString())));
            case "refund.failed" -> byRefund(refund).ifPresent(r ->
                    ledger.recordRefund(r.getId(), new Rejected("Razorpay could not process the refund")));
            default -> log.debug("Ignoring Razorpay webhook event {}", event);
        }
    }

    private Optional<Payment> byOrder(JsonNode payment) {
        String orderId = payment.path("order_id").asString(null);
        return orderId == null ? Optional.empty() : payments.findByProviderAndProviderRef(Provider.RAZORPAY, orderId);
    }

    /** By Razorpay's refund id, or by Rydex's id in the notes when the webhook beat Rydex saving the id. */
    private Optional<Refund> byRefund(JsonNode refund) {
        Optional<Refund> byRef = refunds.findByProviderRef(refund.path("id").asString());
        if (byRef.isPresent()) {
            return byRef;
        }
        try {
            String ours = refund.path("notes").path("refund_id").asString(null);
            return ours == null ? Optional.empty() : refunds.findById(UUID.fromString(ours));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The processor may be recording the same payment at the same moment: one retry settles it. */
    private void record(UUID paymentId, ChargeOutcome outcome) {
        try {
            ledger.recordCharge(paymentId, outcome);
        } catch (OptimisticLockingFailureException e) {
            ledger.recordCharge(paymentId, outcome);
        }
    }
}

package com.rydex.payment.payment;

import com.rydex.payment.payment.PaymentDtos.ConfigView;
import com.rydex.payment.payment.PaymentDtos.PageView;
import com.rydex.payment.payment.PaymentDtos.PaymentView;
import com.rydex.payment.payment.PaymentDtos.VerifyRequest;
import com.rydex.payment.razorpay.RazorpayCheckout;
import com.rydex.payment.security.AuthUser;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments")
@Validated
class PaymentController {

    private final PaymentService payments;
    private final RazorpayCheckout razorpay;

    PaymentController(PaymentService payments, RazorpayCheckout razorpay) {
        this.payments = payments;
        this.razorpay = razorpay;
    }

    @GetMapping("/config")
    @SecurityRequirements
    @Operation(summary = "Which provider takes payments, and the Razorpay key id Checkout needs (public)")
    ConfigView config() {
        return payments.config();
    }

    @GetMapping("/rides/{rideId}")
    @Operation(summary = "The payment for a ride (its rider, its driver, admins). Riders get Razorpay Checkout "
            + "options while a payment awaits them")
    PaymentView forRide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID rideId) {
        return payments.forRide(AuthUser.from(jwt), rideId);
    }

    @GetMapping("/mine")
    @Operation(summary = "A rider's payments, or the payments for a driver's rides, newest first")
    PageView<PaymentView> mine(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "0") @Min(0) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return payments.mine(AuthUser.from(jwt), page, size);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "One payment (its rider, its driver, admins)")
    PaymentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        return payments.get(AuthUser.from(jwt), paymentId);
    }

    @PostMapping("/{paymentId}/retry")
    @Operation(summary = "Pay again after a decline (rider): the mock charges again; Razorpay reopens the same order")
    PaymentView retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        return payments.retry(AuthUser.from(jwt), paymentId);
    }

    @PostMapping("/{paymentId}/razorpay/verify")
    @Operation(summary = "Forward Razorpay Checkout's success response (rider): the signature is checked and the "
            + "payment confirmed with Razorpay")
    PaymentView verify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId,
                       @Valid @RequestBody VerifyRequest req) {
        return razorpay.verify(AuthUser.from(jwt), paymentId, req);
    }

    /** Called by Razorpay, signed with the webhook secret; not part of the client API. */
    @Hidden
    @PostMapping("/webhooks/razorpay")
    ResponseEntity<Void> webhook(@RequestBody String body,
                                 @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        razorpay.webhook(body, signature);
        return ResponseEntity.ok().build();
    }
}

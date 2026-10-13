package com.rydex.payment.payment;

import com.rydex.events.PaymentPayload.Status;
import com.rydex.payment.payment.PaymentDtos.PageView;
import com.rydex.payment.payment.PaymentDtos.PaymentView;
import com.rydex.payment.payment.PaymentDtos.RefundRequest;
import com.rydex.payment.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admins only (see SecurityConfig). */
@RestController
@RequestMapping("/api/admin/payments")
@Tag(name = "Admin")
@Validated
class AdminPaymentController {

    private final PaymentService payments;

    AdminPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    @Operation(summary = "All payments, newest first, optionally by status")
    PageView<PaymentView> list(@RequestParam(required = false) Status status,
                               @RequestParam(defaultValue = "0") @Min(0) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return payments.list(status, page, size);
    }

    @PostMapping("/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Refund all (no amount) or part of a succeeded payment. The refund is PENDING until the "
            + "provider confirms it; the rider is notified then")
    PaymentView refund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId,
                       @Valid @RequestBody RefundRequest req) {
        return payments.refund(AuthUser.from(jwt), paymentId, req.amount(), req.reason());
    }
}

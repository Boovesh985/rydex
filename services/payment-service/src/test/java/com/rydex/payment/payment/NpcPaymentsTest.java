package com.rydex.payment.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rydex.events.PaymentPayload.Kind;
import com.rydex.events.PaymentPayload.Provider;
import com.rydex.payment.config.PaymentProperties;
import com.rydex.payment.provider.PaymentProviders;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Living city (ADR 0016): an automated rider never gets a real (test-mode) gateway order. */
class NpcPaymentsTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentLedger ledger = mock(PaymentLedger.class);
    private final PaymentProviders providers = mock(PaymentProviders.class);
    private final PaymentService service = new PaymentService(payments, mock(RefundRepository.class), ledger,
            mock(PaymentProcessor.class), providers, mock(PaymentProperties.class));

    PaymentService withRazorpayActive() {
        when(providers.active()).thenReturn(Provider.RAZORPAY);
        when(ledger.create(any(), any(), any(), any(), any(), any())).thenReturn(mock(Payment.class));
        return service;
    }

    @Test
    void anNpcRiderPaysThroughTheMockProvider() {
        UUID ride = UUID.randomUUID();
        withRazorpayActive().chargeForRide(ride, UUID.randomUUID(), UUID.randomUUID(), Kind.RIDE_FARE,
                new BigDecimal("120"), true);

        verify(ledger).create(eq(ride), any(), any(), eq(Kind.RIDE_FARE), any(), eq(Provider.MOCK));
    }

    @Test
    void aRealRiderPaysThroughTheConfiguredProvider() {
        UUID ride = UUID.randomUUID();
        withRazorpayActive().chargeForRide(ride, UUID.randomUUID(), UUID.randomUUID(), Kind.RIDE_FARE,
                new BigDecimal("120"), false);

        verify(ledger).create(eq(ride), any(), any(), eq(Kind.RIDE_FARE), any(), eq(Provider.RAZORPAY));
    }
}

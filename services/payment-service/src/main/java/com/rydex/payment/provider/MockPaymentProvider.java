package com.rydex.payment.provider;

import com.rydex.events.PaymentPayload.Provider;
import com.rydex.payment.config.PaymentProperties;
import com.rydex.payment.payment.Payment;
import com.rydex.payment.payment.Refund;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The default provider: charges and refunds succeed at once, without any network call. Charges
 * above {@code rydex.payments.mock.decline-above} are declined, so failures can be exercised.
 */
@Component
class MockPaymentProvider implements PaymentProvider {

    private final PaymentProperties.Mock props;

    MockPaymentProvider(PaymentProperties props) {
        this.props = props.mock();
    }

    @Override
    public Provider id() {
        return Provider.MOCK;
    }

    @Override
    public ChargeOutcome start(Payment payment) {
        String ref = "mock_ch_" + shortId();
        if (payment.getAmount().compareTo(props.declineAbove()) > 0) {
            return new Declined(ref, "Card declined (the mock provider declines charges above Rs "
                    + props.declineAbove().toPlainString() + ")");
        }
        return new Charged(ref, null);
    }

    @Override
    public ChargeOutcome check(Payment payment) {
        return new Waiting();
    }

    @Override
    public RefundOutcome refund(Payment payment, Refund refund) {
        return new Processed("mock_rf_" + shortId());
    }

    @Override
    public RefundOutcome checkRefund(Payment payment, Refund refund) {
        return new Processed(refund.getProviderRef());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}

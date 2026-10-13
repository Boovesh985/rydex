package com.rydex.payment.provider;

import com.rydex.events.PaymentPayload.Provider;
import com.rydex.payment.config.PaymentProperties;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The provider new payments go to ({@code rydex.payments.provider}), and the provider of any
 * existing payment: a payment is always finished, checked and refunded by the provider that
 * started it, even after the configured one changes.
 */
@Component
public class PaymentProviders {

    private final Map<Provider, PaymentProvider> byId = new EnumMap<>(Provider.class);
    private final Provider active;

    PaymentProviders(List<PaymentProvider> providers, PaymentProperties props) {
        providers.forEach(p -> byId.put(p.id(), p));
        this.active = props.provider();
        if (active == Provider.RAZORPAY) {
            props.razorpay().validate(); // explains which keys are missing
        }
        if (!byId.containsKey(active)) {
            throw new IllegalStateException("payment provider " + active + " is not available");
        }
    }

    public Provider active() {
        return active;
    }

    /** Throws {@link ProviderUnavailableException} for a provider that is not configured here. */
    public PaymentProvider get(Provider id) {
        PaymentProvider provider = byId.get(id);
        if (provider == null) {
            throw new ProviderUnavailableException(id + " is not configured on this instance");
        }
        return provider;
    }
}

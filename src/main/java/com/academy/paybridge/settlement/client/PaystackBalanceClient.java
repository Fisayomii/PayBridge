package com.academy.paybridge.settlement.client;

import com.academy.paybridge.shared.paystack.PaystackHttpClient;
import com.academy.paybridge.shared.paystack.PaystackResponse;
import com.academy.paybridge.shared.provider.GatewayUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Paystack {@code GET /balance}: one entry per currency, amounts in the minor unit (kobo). */
@Component
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "paystack", matchIfMissing = true)
public class PaystackBalanceClient implements ProviderBalanceClient {

    private static final ParameterizedTypeReference<PaystackResponse<List<Balance>>> BALANCES =
            new ParameterizedTypeReference<>() {};

    private final PaystackHttpClient paystack;

    public PaystackBalanceClient(PaystackHttpClient paystack) {
        this.paystack = paystack;
    }

    @Override
    public BigDecimal availableBalance(String currency) {
        PaystackResponse<List<Balance>> response = paystack.get("check balance", BALANCES, "/balance");
        if (!response.status() || response.data() == null) {
            throw new GatewayUnavailableException("Paystack balance unavailable: " + response.message(), null);
        }
        return response.data().stream()
                .filter(b -> currency.equalsIgnoreCase(b.currency()))
                .findFirst()
                .map(b -> BigDecimal.valueOf(b.balance()).movePointLeft(2))
                .orElse(BigDecimal.ZERO.setScale(2));
    }

    @Override
    public String providerName() {
        return "PAYSTACK";
    }

    record Balance(String currency, long balance) {}
}

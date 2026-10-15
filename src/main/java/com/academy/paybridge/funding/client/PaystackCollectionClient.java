package com.academy.paybridge.funding.client;

import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.shared.paystack.PaystackHttpClient;
import com.academy.paybridge.shared.paystack.PaystackResponse;
import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Paystack Transactions API: hosted checkout (card, bank transfer, USSD) and verification. */
@Component
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "paystack", matchIfMissing = true)
public class PaystackCollectionClient implements CollectionGateway {

    private static final ParameterizedTypeReference<PaystackResponse<InitData>> INIT = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<PaystackResponse<VerifyData>> VERIFY = new ParameterizedTypeReference<>() {};

    private final PaystackHttpClient paystack;

    public PaystackCollectionClient(PaystackHttpClient paystack) {
        this.paystack = paystack;
    }

    @Override
    public CheckoutSession initializeCheckout(String email, BigDecimal amount, String currency, String reference,
                                              String callbackUrl) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("amount", Money.of(amount, currency).toMinorUnits());
        payload.put("currency", currency);
        payload.put("reference", reference);
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            payload.put("callback_url", callbackUrl);
        }
        PaystackResponse<InitData> response = paystack.post("initialize checkout", INIT, "/transaction/initialize", payload);
        if (!response.status() || response.data() == null || response.data().authorizationUrl() == null) {
            throw new GatewayRejectedException("Could not start checkout: " + response.message());
        }
        return new CheckoutSession(response.data().authorizationUrl(), response.data().accessCode());
    }

    @Override
    @Retry(name = "paystack")
    @CircuitBreaker(name = "paystack")
    public ChargeVerification verifyCharge(String reference) {
        PaystackResponse<VerifyData> response;
        try {
            response = paystack.get("verify charge", VERIFY, "/transaction/verify/{reference}", reference);
        } catch (GatewayRejectedException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("not found")) {
                return new ChargeVerification(ChargeStatus.NOT_FOUND, 0, null, null, ex.getMessage());
            }
            throw ex;
        }
        VerifyData d = response.data();
        if (!response.status() || d == null) {
            return new ChargeVerification(ChargeStatus.NOT_FOUND, 0, null, null, response.message());
        }
        ChargeStatus status = switch (d.status() == null ? "" : d.status().toLowerCase()) {
            case "success" -> ChargeStatus.SUCCESS;
            case "failed", "reversed" -> ChargeStatus.FAILED;
            case "abandoned" -> ChargeStatus.ABANDONED;
            default -> ChargeStatus.PENDING; // ongoing, pending, processing, queued
        };
        return new ChargeVerification(status, d.amount() == null ? 0 : d.amount(), d.currency(),
                d.id() == null ? null : String.valueOf(d.id()), d.gatewayResponse());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InitData(
            @JsonProperty("authorization_url") String authorizationUrl,
            @JsonProperty("access_code") String accessCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VerifyData(
            Long id,
            String status,
            Long amount,
            String currency,
            @JsonProperty("gateway_response") String gatewayResponse
    ) {}
}

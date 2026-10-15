package com.academy.paybridge.funding.client;

import com.academy.paybridge.shared.money.Money;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline stand-in for Paystack checkout ({@code paybridge.payments.provider=simulated}). A checkout stays
 * PENDING until {@link #pay} (exposed through the sandbox simulation endpoint) marks it paid.
 */
@Component
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "simulated")
public class SimulatedCollectionGateway implements CollectionGateway {

    private final Map<String, ChargeVerification> charges = new ConcurrentHashMap<>();

    @Override
    public CheckoutSession initializeCheckout(String email, BigDecimal amount, String currency, String reference,
                                              String callbackUrl) {
        charges.putIfAbsent(reference, new ChargeVerification(ChargeStatus.PENDING,
                Money.of(amount, currency).toMinorUnits(), currency, null, "Awaiting payment"));
        return new CheckoutSession("https://checkout.simulated.paybridge.local/" + reference, "sim_" + reference);
    }

    @Override
    public ChargeVerification verifyCharge(String reference) {
        return charges.getOrDefault(reference,
                new ChargeVerification(ChargeStatus.NOT_FOUND, 0, null, null, "Transaction reference not found"));
    }

    /** Simulates the customer completing (or failing) payment, optionally for a different amount than requested. */
    public void pay(String reference, boolean success, Long overrideAmountMinor) {
        ChargeVerification current = charges.get(reference);
        if (current == null) {
            throw new IllegalArgumentException("Unknown checkout " + reference);
        }
        charges.put(reference, new ChargeVerification(
                success ? ChargeStatus.SUCCESS : ChargeStatus.FAILED,
                overrideAmountMinor != null ? overrideAmountMinor : current.amountMinor(),
                current.currency(),
                "SIM-" + reference,
                success ? "Approved" : "Declined"));
    }
}

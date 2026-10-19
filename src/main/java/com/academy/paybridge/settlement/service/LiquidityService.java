package com.academy.paybridge.settlement.service;

import com.academy.paybridge.settlement.client.ProviderBalanceClient;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.api.PayoutReconciliationApi;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Can we pay what we owe? Compares the balance we hold with the payout provider (Paystack) against the bank
 * payouts already in flight. A payout the provider cannot fund fails at the provider, so this has to be
 * watched and topped up before it runs dry.
 */
@Service
public class LiquidityService {

    private static final Logger log = LogManager.getLogger(LiquidityService.class);

    private final ProviderBalanceClient balanceClient;
    private final PayoutReconciliationApi payoutApi;
    private final BigDecimal lowBalanceThreshold;

    public LiquidityService(ProviderBalanceClient balanceClient, PayoutReconciliationApi payoutApi,
                            @Value("${paybridge.settlement.liquidity.low-balance-threshold:100000.00}")
                            BigDecimal lowBalanceThreshold) {
        this.balanceClient = balanceClient;
        this.payoutApi = payoutApi;
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    public LiquidityView ngnLiquidity() {
        BigDecimal balance;
        try {
            balance = balanceClient.availableBalance("NGN");
        } catch (RuntimeException ex) {
            throw PayBridgeException.serviceUnavailable("Could not read the " + balanceClient.providerName()
                    + " balance: " + ex.getMessage());
        }
        BigDecimal inFlight = payoutApi.openPayoutTotal("NGN");
        BigDecimal headroom = balance.subtract(inFlight);
        Status status = headroom.signum() < 0 ? Status.INSUFFICIENT
                : headroom.compareTo(lowBalanceThreshold) < 0 ? Status.LOW : Status.OK;
        if (status != Status.OK) {
            log.warn("LIQUIDITY {}: {} NGN balance {} vs {} in flight; top up the provider balance",
                    status, balanceClient.providerName(), balance, inFlight);
        }
        return new LiquidityView(balanceClient.providerName(), "NGN", balance, inFlight, headroom,
                lowBalanceThreshold, status, Instant.now());
    }

    public enum Status {
        OK,
        /** Headroom under the configured threshold: top up soon. */
        LOW,
        /** Payouts already in flight exceed the balance: some will fail at the provider. */
        INSUFFICIENT
    }

    /**
     * @param providerBalance  read live from the provider
     * @param payoutsInFlight  PENDING/PROCESSING bank payouts (what GL-NGN-PAYOUT-CLEARING holds)
     * @param headroom         providerBalance - payoutsInFlight
     */
    public record LiquidityView(String provider, String currency, BigDecimal providerBalance,
                                BigDecimal payoutsInFlight, BigDecimal headroom, BigDecimal lowBalanceThreshold,
                                Status status, Instant asOf) {}
}

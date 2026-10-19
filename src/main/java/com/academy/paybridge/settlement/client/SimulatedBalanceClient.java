package com.academy.paybridge.settlement.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Offline stand-in: a fixed float, configurable to demo the LOW and INSUFFICIENT states. */
@Component
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "simulated")
public class SimulatedBalanceClient implements ProviderBalanceClient {

    private final BigDecimal balance;

    public SimulatedBalanceClient(@Value("${paybridge.settlement.liquidity.simulated-balance:2000000.00}") BigDecimal balance) {
        this.balance = balance;
    }

    @Override
    public BigDecimal availableBalance(String currency) {
        return "NGN".equalsIgnoreCase(currency) ? balance : BigDecimal.ZERO.setScale(2);
    }

    @Override
    public String providerName() {
        return "SIMULATED";
    }
}

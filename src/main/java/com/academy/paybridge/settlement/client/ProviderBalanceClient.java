package com.academy.paybridge.settlement.client;

import java.math.BigDecimal;

/** What the payout provider says we can still pay out from: our balance held with it. */
public interface ProviderBalanceClient {

    /** Available balance in major units (naira), or throws if the provider cannot be reached. */
    BigDecimal availableBalance(String currency);

    String providerName();
}

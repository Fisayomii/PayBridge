package com.academy.paybridge.fx.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A market data source for the USD/NGN mid rate. In production this is the dealer bank's rate API or the
 * published NFEM rate; this build uses {@link OpenErApiRateFeed} (free, no key, updated daily).
 */
public interface RateFeed {

    /** Throws if the feed cannot be reached or returns no NGN rate. */
    FeedRate fetchUsdNgn();

    String name();

    /** @param providerUpdatedAt when the provider itself last updated the rate */
    record FeedRate(BigDecimal midRate, Instant providerUpdatedAt) {}
}

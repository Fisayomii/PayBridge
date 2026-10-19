package com.academy.paybridge.fx.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param initialMidRate     NGN per USD used only while no rate has ever been stored and the feed is off
 * @param spreadPercent      how far below mid the recipient's rate is (PayBridge's FX margin)
 * @param remittanceFee      flat USD fee per remittance, charged to the partner
 * @param quoteTtl           how long a quoted rate is honoured
 * @param maxRateMovePercent deviation guard: a new mid rate further than this from the current one is refused
 * @param feed               the live market rate feed
 */
@ConfigurationProperties(prefix = "paybridge.fx")
public record FxProperties(
        BigDecimal initialMidRate,
        BigDecimal spreadPercent,
        BigDecimal remittanceFee,
        Duration quoteTtl,
        BigDecimal maxRateMovePercent,
        Feed feed
) {
    /**
     * @param enabled  poll the feed and refuse quotes when its rate is stale
     * @param baseUrl  feed host
     * @param refresh  how often to poll
     * @param maxAge   quotes are refused if the newest stored rate is older than this
     */
    public record Feed(boolean enabled, String baseUrl, Duration refresh, Duration maxAge) {}

    public boolean feedEnabled() {
        return feed != null && feed.enabled();
    }
}

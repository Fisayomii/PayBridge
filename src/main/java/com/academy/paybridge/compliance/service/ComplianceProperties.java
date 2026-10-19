package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Limits per CBN KYC tier. The defaults in application.yml follow the CBN tiered-KYC framework as
 * commonly applied by Nigerian wallets; confirm the exact figures with your compliance officer before go-live.
 */
@ConfigurationProperties(prefix = "paybridge.compliance")
public record ComplianceProperties(
        Map<Integer, TierLimits> tiers,
        Velocity velocity,
        Set<String> sanctionedWallets,
        Set<String> blockedNarrationKeywords,
        Remittance remittance
) {
    /**
     * @param blockedCountries   sender countries we cannot accept money from (comprehensive sanctions)
     * @param watchlist          names screened against sender and recipient (stand-in for OFAC/UN/UK/EU lists)
     * @param reviewThresholdUsd remittances at or above this go to manual review
     */
    public record Remittance(Set<String> blockedCountries, List<String> watchlist, BigDecimal reviewThresholdUsd) {}

    public record TierLimits(
            BigDecimal singleTransferLimit,
            BigDecimal dailyTransferLimit,
            /** Null means no cap. */
            BigDecimal maxBalance
    ) {}

    public record Velocity(int maxTransfers, Duration window) {}

    public TierLimits limitsFor(int tier) {
        TierLimits limits = tiers == null ? null : tiers.get(tier);
        if (limits == null) {
            throw new IllegalStateException("No compliance limits configured for KYC tier " + tier);
        }
        return limits;
    }
}

package com.academy.paybridge.notification.api;

import java.math.BigDecimal;

/** A wallet was credited (or administratively debited) from outside the transfer system. */
public record FundingEvent(
        String reference,
        String walletNumber,
        String ownerName,
        String email,
        String channel,
        String direction,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter
) {}

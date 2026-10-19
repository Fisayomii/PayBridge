package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuoteDto(
        UUID id,
        String reference,
        String sourceCurrency,
        String targetCurrency,
        /** The USD value being sent to the recipient. */
        BigDecimal sourceAmount,
        /** PayBridge's fee in USD, on top of sourceAmount. */
        BigDecimal fee,
        /** sourceAmount + fee: what leaves the partner's prefund. */
        BigDecimal totalCost,
        BigDecimal midRate,
        /** NGN per USD the recipient gets: midRate minus the spread. */
        BigDecimal rate,
        /** NGN credited to the recipient, rounded down to the kobo. */
        BigDecimal targetAmount,
        Instant expiresAt,
        boolean used,
        Instant createdAt
) {}

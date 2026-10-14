package com.academy.paybridge.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountDto(
        UUID id,
        String walletNumber,
        /** Null for INTERNAL GL accounts and for legacy ownerless demo wallets. */
        UUID customerId,
        String ownerName,
        String email,
        BigDecimal balance,
        String currency,
        AccountType type,
        AccountStatus status,
        Instant createdAt
) {}

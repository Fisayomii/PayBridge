package com.academy.paybridge.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryDto(
        UUID id,
        String transactionReference,
        UUID accountId,
        String entryType,
        BigDecimal amount,
        String currency,
        String narration,
        Instant createdAt
) {}
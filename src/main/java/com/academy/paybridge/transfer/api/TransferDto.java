package com.academy.paybridge.transfer.api;

import com.academy.paybridge.ledger.api.LedgerEntryDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferDto(
        UUID id,
        String reference,
        String idempotencyKey,
        String sourceWalletNumber,
        String destinationWalletNumber,
        String resolvedBeneficiaryName,
        String recipientCode,
        String gatewayTransferCode,
        BigDecimal amount,
        String currency,
        String status,
        String narration,
        BigDecimal sourceWalletBalanceAfter,
        BigDecimal destinationWalletBalanceAfter,
        List<LedgerEntryDto> ledgerEntries,
        Instant createdAt
) {}
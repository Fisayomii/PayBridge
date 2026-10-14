package com.academy.paybridge.transfer.api;

import com.academy.paybridge.ledger.api.LedgerEntryDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transfer receipt as seen by the sender. Deliberately excludes the receiver's balance.
 */
public record TransferDto(
        UUID id,
        String reference,
        String type,
        String status,
        String sourceWalletNumber,
        /** INTERNAL only. */
        String destinationWalletNumber,
        /** EXTERNAL only, masked. */
        String beneficiaryAccountNumber,
        /** EXTERNAL only. */
        String beneficiaryBankCode,
        String beneficiaryName,
        BigDecimal amount,
        BigDecimal fee,
        /** amount + fee: what left the sender's wallet (refunded in full if a payout fails). */
        BigDecimal totalDebit,
        String currency,
        String narration,
        String failureReason,
        String gatewayTransferCode,
        /** Sender's balance at the time this receipt was produced. */
        BigDecimal sourceWalletBalance,
        List<LedgerEntryDto> ledgerEntries,
        Instant createdAt,
        Instant completedAt
) {}

package com.academy.paybridge.notification.api;

import java.math.BigDecimal;

/**
 * Published on every customer-visible transfer state change (internal success, payout hold,
 * payout success, payout failure/reversal). Delivered after the transaction commits.
 */
public record TransferEvent(
        String reference,
        String transferType,
        String sourceWalletNumber,
        String senderEmail,
        /** Wallet number for INTERNAL, masked bank account for EXTERNAL. */
        String destination,
        /** Null for EXTERNAL payouts: the beneficiary is not a PayBridge customer. */
        String receiverEmail,
        String beneficiaryName,
        BigDecimal amount,
        String currency,
        BigDecimal senderBalanceAfter,
        BigDecimal receiverBalanceAfter,
        String status,
        String narration
) {}

package com.academy.paybridge.notification.api;

import java.math.BigDecimal;

public record TransferEvent(
        String reference,
        String sourceWalletNumber,
        String senderEmail,
        String destinationWalletNumber,
        String receiverEmail,
        String beneficiaryName,
        BigDecimal amount,
        String currency,
        BigDecimal senderBalanceAfter,
        BigDecimal receiverBalanceAfter,
        String status,
        String narration
) {}
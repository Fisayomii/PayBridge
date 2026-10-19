package com.academy.paybridge.notification.api;

import java.math.BigDecimal;

/** A remittance from abroad was credited to a PayBridge wallet. */
public record RemittanceEvent(
        String reference,
        String recipientWalletNumber,
        String recipientName,
        String recipientEmail,
        String senderName,
        String senderCountry,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter
) {}

package com.academy.paybridge.remittance.api;

import java.math.BigDecimal;
import java.time.Instant;

public record RemittanceDto(
        String reference,
        String partnerReference,
        String status,
        String senderName,
        String senderCountry,
        String recipientName,
        String recipientWalletNumber,
        String purpose,
        BigDecimal sourceAmount,
        BigDecimal fee,
        /** sourceAmount + fee, taken from the partner's prefund (returned in full if rejected or failed). */
        BigDecimal totalCharged,
        String sourceCurrency,
        BigDecimal rate,
        BigDecimal targetAmount,
        String targetCurrency,
        /** Why screening held or blocked it. */
        String screeningNotes,
        String reviewNote,
        String failureReason,
        Instant createdAt,
        Instant completedAt
) {}

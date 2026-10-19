package com.academy.paybridge.compliance.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Compliance owns the rules (sanctions, KYC-tier limits, velocity); callers supply the facts.
 * Usage figures must be computed while the source wallet is locked so concurrent transfers cannot
 * both squeeze under a limit.
 */
public interface ComplianceApi {

    /** Throws if the transfer must not proceed. */
    void screenTransfer(TransferScreening screening);

    /** Throws if crediting {@code amount} would push a wallet above its KYC tier's maximum balance. */
    void checkInboundCredit(int kycTier, BigDecimal currentBalance, BigDecimal amount);

    /** Look-back window the caller must count transfers over for the velocity rule. */
    Duration velocityWindow();

    /**
     * Screens an inbound remittance. Unlike transfers it does not throw for a possible match: a REVIEW
     * decision parks the remittance for an analyst instead of rejecting a customer on a fuzzy name hit.
     */
    ScreeningResult screenRemittance(RemittanceScreening screening);

    enum Decision { CLEAR, REVIEW, BLOCK }

    record ScreeningResult(Decision decision, List<String> reasons) {
        public boolean isClear() { return decision == Decision.CLEAR; }
    }

    record RemittanceScreening(
            String senderName,
            /** ISO 3166 alpha-2. */
            String senderCountry,
            /** Recipient name as the partner sent it. */
            String recipientName,
            /** Name on the PayBridge wallet being credited. */
            String recipientAccountName,
            BigDecimal amountUsd,
            String purpose
    ) {}

    record TransferScreening(
            String sourceWalletNumber,
            /** Wallet number, or "bankCode:accountNumber" for external payouts. */
            String destination,
            BigDecimal amount,
            String narration,
            int senderKycTier,
            /** Sum of the sender's non-failed outgoing transfers today (Africa/Lagos), excluding this one. */
            BigDecimal outgoingToday,
            /** Number of transfers the sender started within {@link #velocityWindow()}. */
            long recentTransferCount
    ) {}
}

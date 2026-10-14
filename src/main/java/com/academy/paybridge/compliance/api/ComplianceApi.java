package com.academy.paybridge.compliance.api;

import java.math.BigDecimal;
import java.time.Duration;

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

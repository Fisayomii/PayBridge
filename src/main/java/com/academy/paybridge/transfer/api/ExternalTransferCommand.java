package com.academy.paybridge.transfer.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param initiatorId       customer instructing the payout; must own the source wallet
 * @param beneficiaryAccountNumber / beneficiaryBankCode  the destination, or null when {@code beneficiaryId} is given
 * @param beneficiaryId     a saved EXTERNAL beneficiary of the initiator (skips name enquiry), or null
 * @param saveBeneficiary   save the destination as a beneficiary once funds are held
 */
public record ExternalTransferCommand(
        UUID initiatorId,
        String sourceWalletNumber,
        String beneficiaryAccountNumber,
        String beneficiaryBankCode,
        UUID beneficiaryId,
        BigDecimal amount,
        String narration,
        String idempotencyKey,
        boolean saveBeneficiary
) {}

package com.academy.paybridge.transfer.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param initiatorId           customer instructing the transfer; must own the source wallet
 * @param destinationWalletNumber recipient wallet, or null when {@code beneficiaryId} is given
 * @param beneficiaryId         a saved INTERNAL beneficiary of the initiator, or null
 * @param saveBeneficiary       save the recipient as a beneficiary after a successful transfer
 */
public record InternalTransferCommand(
        UUID initiatorId,
        String sourceWalletNumber,
        String destinationWalletNumber,
        UUID beneficiaryId,
        BigDecimal amount,
        String narration,
        String idempotencyKey,
        boolean saveBeneficiary
) {}

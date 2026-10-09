package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Set;

@Service
public class ComplianceService implements ComplianceApi {

    private static final Logger log = LogManager.getLogger(ComplianceService.class);

    // CBN Tier-3 individual single transfer limit (NGN 5,000,000.00)
    private static final BigDecimal SINGLE_TRANSFER_LIMIT_NGN = new BigDecimal("5000000.00");

    // Simulated AML / Sanctions watchlist
    private static final Set<String> SANCTIONED_WALLETS = Set.of("WALLET-9999", "WALLET-BLACKLIST");

    @Override
    public void validateTransfer(String sourceWalletNumber, String destinationWalletNumber, BigDecimal amount, String narration) {
        if (SANCTIONED_WALLETS.contains(sourceWalletNumber.toUpperCase())
                || SANCTIONED_WALLETS.contains(destinationWalletNumber.toUpperCase())) {
            throw PayBridgeException.badRequest("COMPLIANCE_BLOCK: Wallet is flagged on the AML/Sanctions watchlist");
        }

        if (amount.compareTo(SINGLE_TRANSFER_LIMIT_NGN) > 0) {
            throw PayBridgeException.badRequest("COMPLIANCE_LIMIT_EXCEEDED: Amount NGN " + amount
                    + " exceeds single transfer limit of NGN " + SINGLE_TRANSFER_LIMIT_NGN);
        }

        if (narration != null && narration.toUpperCase().contains("FRAUD")) {
            throw PayBridgeException.badRequest("COMPLIANCE_BLOCK: Transfer flagged by AML keyword screening");
        }

        log.info("[COMPLIANCE PASSED] Screened transfer of NGN {} from {} to {}",
                amount, sourceWalletNumber, destinationWalletNumber);
    }
}
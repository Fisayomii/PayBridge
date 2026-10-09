package com.academy.paybridge.compliance.api;

import java.math.BigDecimal;

public interface ComplianceApi {
    void validateTransfer(String sourceWalletNumber, String destinationWalletNumber, BigDecimal amount, String narration);
}
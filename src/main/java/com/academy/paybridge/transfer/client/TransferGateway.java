package com.academy.paybridge.transfer.client;

import java.math.BigDecimal;

public interface TransferGateway {

    BankResolutionResult resolveBankAccount(String accountNumber, String bankCode);

    String createTransferRecipient(String accountName, String accountNumber, String bankCode);

    GatewayPayoutResult initiateTransfer(String recipientCode, BigDecimal amountNgn, String reference, String narration);

    record BankResolutionResult(
            String accountNumber,
            String accountName,
            String bankCode,
            String providerMode
    ) {}

    record GatewayPayoutResult(
            String gatewayReference,
            String recipientCode,
            String status,
            String message
    ) {}
}
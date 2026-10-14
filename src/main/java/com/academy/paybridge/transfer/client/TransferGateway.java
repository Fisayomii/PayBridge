package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.academy.paybridge.shared.provider.GatewayUnavailableException;

import java.math.BigDecimal;

/**
 * Outbound bank payout provider (Paystack today; Flutterwave/Fincra can be swapped in behind this interface).
 *
 * <p>Error contract, which the payout flow relies on to decide whether money may be returned to the customer:
 * <ul>
 *   <li>{@link GatewayRejectedException} - the provider definitively refused; nothing was (or will be) sent.</li>
 *   <li>{@link GatewayUnavailableException} - outcome unknown (timeout, 5xx). The payout may or may not exist.</li>
 * </ul>
 */
public interface TransferGateway {

    BankResolutionResult resolveBankAccount(String accountNumber, String bankCode);

    String createTransferRecipient(String accountName, String accountNumber, String bankCode);

    /** Must be safe to call again with the same {@code reference}: the provider de-duplicates on it. */
    PayoutResult initiateTransfer(String recipientCode, BigDecimal amount, String currency,
                                  String reference, String narration);

    /** Returns {@link PayoutStatus#NOT_FOUND} if the provider has no payout with this reference. */
    PayoutResult verifyTransfer(String reference);

    enum PayoutStatus { PENDING, SUCCESS, FAILED, REVERSED, NOT_FOUND }

    record BankResolutionResult(
            String accountNumber,
            String accountName,
            String bankCode,
            String providerMode
    ) {}

    record PayoutResult(
            PayoutStatus status,
            String gatewayReference,
            String message
    ) {}
}

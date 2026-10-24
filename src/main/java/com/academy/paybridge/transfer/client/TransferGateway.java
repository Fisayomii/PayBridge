package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.academy.paybridge.shared.provider.GatewayUnavailableException;

import java.math.BigDecimal;
import java.util.List;

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

    /**
     * Nigerian banks that accept NGN payouts, as the provider lists them. The default is a short built-in
     * list for providers (and the offline simulator) that have no bank directory.
     */
    default List<Bank> listBanks() {
        return FALLBACK_BANKS;
    }

    List<Bank> FALLBACK_BANKS = List.of(
            new Bank("044", "Access Bank"), new Bank("050", "Ecobank Nigeria"), new Bank("070", "Fidelity Bank"),
            new Bank("011", "First Bank of Nigeria"), new Bank("214", "First City Monument Bank"),
            new Bank("058", "Guaranty Trust Bank"), new Bank("50211", "Kuda Bank"), new Bank("50515", "Moniepoint MFB"),
            new Bank("999992", "OPay Digital Services Limited (OPay)"), new Bank("999991", "PalmPay"),
            new Bank("076", "Polaris Bank"), new Bank("221", "Stanbic IBTC Bank"), new Bank("232", "Sterling Bank"),
            new Bank("032", "Union Bank of Nigeria"), new Bank("033", "United Bank For Africa"),
            new Bank("035", "Wema Bank"), new Bank("057", "Zenith Bank"));

    record Bank(String code, String name) {}

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

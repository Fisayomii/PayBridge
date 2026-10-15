package com.academy.paybridge.transfer.api;

/**
 * Entry point for other modules (e.g. remittance paying out NGN to a bank account).
 * Both operations are idempotent per (source wallet, idempotency key).
 */
public interface TransferApi {

    /** Wallet-to-wallet book transfer. Atomic: returns SUCCESS or throws, never half-done. */
    TransferDto transferInternal(InternalTransferCommand command);

    /**
     * Payout to an external bank account. Funds are held immediately; the returned status is usually
     * PENDING or PROCESSING and becomes SUCCESS / FAILED / REVERSED asynchronously.
     */
    TransferDto transferExternal(ExternalTransferCommand command);

    TransferDto getByReference(String reference);
}

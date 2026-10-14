package com.academy.paybridge.transfer.domain;

/**
 * INTERNAL transfers are created directly as SUCCESS.
 * EXTERNAL payouts move: PENDING (funds held) -> PROCESSING (provider accepted) -> SUCCESS | FAILED,
 * and SUCCESS -> REVERSED if the provider later returns the funds.
 */
public enum TransferStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    REVERSED;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED || this == REVERSED;
    }
}

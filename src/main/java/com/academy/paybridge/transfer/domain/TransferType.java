package com.academy.paybridge.transfer.domain;

public enum TransferType {
    /** Book transfer between two PayBridge wallets. Settles instantly, never touches a payment provider. */
    INTERNAL,
    /** Payout from a PayBridge wallet to an external bank account via the transfer gateway (NIP). */
    EXTERNAL
}

package com.academy.paybridge.account.api;

public enum AccountType {
    /** A customer-owned wallet. Can never go below zero. */
    CUSTOMER,
    /** A PayBridge-owned general-ledger account (clearing, settlement, fees). Never exposed to customers. */
    INTERNAL
}

package com.academy.paybridge.account.api;

public enum AccountStatus {
    ACTIVE,
    /** Credits allowed, customer-initiated debits blocked (e.g. pending investigation, KYC lapse). */
    POST_NO_DEBIT,
    /** No customer-initiated debits or credits. System refunds/reversals still land. */
    FROZEN;

    public boolean allowsCustomerDebit() {
        return this == ACTIVE;
    }

    public boolean allowsCustomerCredit() {
        return this != FROZEN;
    }
}

package com.academy.paybridge.customer.api;

public enum CustomerStatus {
    ACTIVE,
    /** Cannot log in, refresh tokens, or move money. */
    SUSPENDED
}

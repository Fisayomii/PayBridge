package com.academy.paybridge.shared.security;

public enum Role {
    CUSTOMER,
    ADMIN,
    /** A licensed money transfer operator abroad that sends remittances through the partner API. */
    PARTNER
}

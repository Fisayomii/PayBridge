package com.academy.paybridge.funding.domain;

public enum FundingChannel {
    /** Customer pays by card / bank transfer / USSD on Paystack's hosted checkout. */
    PAYSTACK_CHECKOUT,
    /** Operations credit or debit with a recorded reason (corrections, sandbox top-ups). */
    ADMIN_ADJUSTMENT
}

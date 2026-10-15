package com.academy.paybridge.funding.domain;

public enum FundingStatus {
    /** Checkout created; waiting for the customer to pay. */
    PENDING,
    SUCCESS,
    FAILED,
    /** Customer never completed the checkout. */
    ABANDONED
}

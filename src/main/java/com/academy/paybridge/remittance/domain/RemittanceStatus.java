package com.academy.paybridge.remittance.domain;

public enum RemittanceStatus {
    /** Partner funds taken into GL-USD-REMITTANCE-CLEARING; waiting for an analyst. */
    PENDING_REVIEW,
    /** Recipient wallet credited. Final. */
    COMPLETED,
    /** Blocked by screening or rejected by an analyst; partner refunded if anything was taken. Final. */
    REJECTED,
    /** Approved but the payout could not be made (e.g. wallet frozen meanwhile); partner refunded. Final. */
    FAILED;

    public boolean isFinal() {
        return this != PENDING_REVIEW;
    }
}

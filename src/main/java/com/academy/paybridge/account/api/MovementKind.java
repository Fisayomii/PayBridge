package com.academy.paybridge.account.api;

/** Who is moving the money; decides whether account status (freeze / post-no-debit) applies. */
public enum MovementKind {
    /** A customer instruction (transfer, payout hold). Blocked by FROZEN / POST_NO_DEBIT. */
    CUSTOMER_INITIATED,
    /**
     * PayBridge settling something already decided: payout refunds and reversals, confirmed card/bank
     * collections, audited admin adjustments. Must not be blocked by a freeze, or a refund owed to a
     * frozen customer would be lost.
     */
    SYSTEM
}

package com.academy.paybridge.account.api;

/**
 * Wallet numbers of the PayBridge-owned GL accounts seeded by V4/V5 migrations.
 */
public final class InternalAccounts {

    /** Holds customer funds between the debit and the provider confirming (or failing) an external payout. */
    public static final String NGN_PAYOUT_CLEARING = "GL-NGN-PAYOUT-CLEARING";

    /** Accumulates payouts confirmed by Paystack; the settlement module reconciles it against Paystack's balance. */
    public static final String NGN_PAYSTACK_SETTLEMENT = "GL-NGN-PAYSTACK-SETTLEMENT";

    /** Counterpart of customer wallet top-ups collected through Paystack (card / bank transfer checkout). */
    public static final String NGN_PAYSTACK_COLLECTIONS = "GL-NGN-PAYSTACK-COLLECTIONS";

    /** Transfer fees charged to customers. */
    public static final String NGN_FEE_INCOME = "GL-NGN-FEE-INCOME";

    /** Counterpart of audited manual credits/debits by operations staff. */
    public static final String NGN_ADJUSTMENTS = "GL-NGN-ADJUSTMENTS";

    private InternalAccounts() {}
}

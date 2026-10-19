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

    // ---- Remittance corridor (V6). Each currency balances on its own.

    /** USD we hold at our bank. Partner prefunds arrive here; the daily FX deal sells from here. */
    public static final String USD_NOSTRO = "GL-USD-NOSTRO";

    /** Partner money taken for a remittance that is waiting for compliance review. */
    public static final String USD_REMITTANCE_CLEARING = "GL-USD-REMITTANCE-CLEARING";

    /** USD bought from partners for paid-out remittances, not yet sold at the bank. */
    public static final String USD_FX_POSITION = "GL-USD-FX-POSITION";

    /** Remittance fees charged to partners. */
    public static final String USD_REMITTANCE_FEE_INCOME = "GL-USD-REMITTANCE-FEE-INCOME";

    /** NGN paid out for remittances, not yet covered by an FX deal. */
    public static final String NGN_FX_POSITION = "GL-NGN-FX-POSITION";

    /** NGN we hold at our bank. The daily FX deal credits it. */
    public static final String NGN_NOSTRO = "GL-NGN-NOSTRO";

    /** Realised gain (positive) or loss between quoted rates and the rate the bank executed. */
    public static final String NGN_FX_PNL = "GL-NGN-FX-PNL";

    private InternalAccounts() {}
}

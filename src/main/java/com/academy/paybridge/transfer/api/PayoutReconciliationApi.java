package com.academy.paybridge.transfer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the settlement module needs from transfers: the provider's view of each payout, a way to accept
 * that view, and the money currently sitting in payout clearing. The transfer module keeps the gateway.
 */
public interface PayoutReconciliationApi {

    /** Every external payout created in [from, to), each checked against the provider. */
    List<PayoutCheck> checkPayoutsWithProvider(Instant from, Instant to);

    /** Fetches the provider's status for one payout and applies it (refunding if the provider says so). */
    TransferDto acceptProviderStatus(String reference);

    /** Sum of payouts still PENDING or PROCESSING: what GL payout clearing should hold. */
    BigDecimal openPayoutTotal(String currency);

    record PayoutCheck(
            String reference,
            BigDecimal amount,
            String currency,
            String ledgerStatus,
            /** PENDING, SUCCESS, FAILED, REVERSED, NOT_FOUND, or UNAVAILABLE if the provider could not be asked. */
            String providerStatus,
            String providerMessage
    ) {}
}

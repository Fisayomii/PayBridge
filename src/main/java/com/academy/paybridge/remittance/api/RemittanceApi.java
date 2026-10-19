package com.academy.paybridge.remittance.api;

import java.math.BigDecimal;

/** What settlement needs from remittance. */
public interface RemittanceApi {

    /** Partner money taken for remittances still in review: what GL-USD-REMITTANCE-CLEARING should hold. */
    BigDecimal heldForReviewTotal();
}

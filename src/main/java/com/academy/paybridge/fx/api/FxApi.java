package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Prices USD -> NGN. A quote is a promise: the rate is honoured until it expires, and only once.
 */
public interface FxApi {

    /** Prices {@code sourceAmount} USD at the current mid rate minus the spread, plus the flat fee. */
    QuoteDto createQuote(UUID partnerId, BigDecimal sourceAmount);

    /**
     * Locks the quote and marks it used, inside the caller's transaction. Throws if it belongs to someone
     * else, has expired, or was already used, so a rate can never be applied twice.
     */
    QuoteDto consumeQuote(String quoteReference, UUID partnerId);

    /** Current reference mid rate for USD/NGN (NGN per 1 USD). */
    BigDecimal currentMidRate();
}

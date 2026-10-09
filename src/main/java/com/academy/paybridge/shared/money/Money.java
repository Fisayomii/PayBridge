package com.academy.paybridge.shared.money;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Embeddable
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(currency, "Currency cannot be null");
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        currency = currency.toUpperCase();
    }

    public static Money ngn(BigDecimal amount) {
        return new Money(amount, "NGN");
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public long toMinorUnits() {
        // Converts Naira to Kobo (e.g., 5000.00 -> 500000) for Paystack
        return amount.multiply(BigDecimal.valueOf(100)).longValueExact();
    }
}
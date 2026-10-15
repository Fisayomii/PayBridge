package com.academy.paybridge.funding.client;

import java.math.BigDecimal;

/** Inbound payments provider: hosted checkout plus server-side verification of the charge. */
public interface CollectionGateway {

    CheckoutSession initializeCheckout(String email, BigDecimal amount, String currency, String reference,
                                       String callbackUrl);

    /** Server-to-server lookup. The only source of truth for crediting a wallet; webhooks just trigger it. */
    ChargeVerification verifyCharge(String reference);

    enum ChargeStatus { SUCCESS, FAILED, ABANDONED, PENDING, NOT_FOUND }

    record CheckoutSession(String authorizationUrl, String accessCode) {}

    record ChargeVerification(
            ChargeStatus status,
            /** Amount actually charged, in minor units (kobo). */
            long amountMinor,
            String currency,
            String providerReference,
            String message
    ) {}
}

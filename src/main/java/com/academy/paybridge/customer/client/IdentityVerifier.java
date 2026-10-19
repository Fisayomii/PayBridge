package com.academy.paybridge.customer.client;

/**
 * Looks up a government identity record. Throws
 * {@link com.academy.paybridge.shared.provider.GatewayRejectedException} when the provider definitively has no
 * such record (a failed verification), and
 * {@link com.academy.paybridge.shared.provider.GatewayUnavailableException} when the outcome is unknown
 * (nothing is recorded; the customer can retry).
 */
public interface IdentityVerifier {

    BvnRecord lookupBvn(String bvn);

    String providerName();

    record BvnRecord(String firstName, String middleName, String lastName, String dateOfBirth) {}
}

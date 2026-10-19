package com.academy.paybridge.customer.client;

import com.academy.paybridge.shared.provider.GatewayRejectedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Offline stand-in for the identity provider. Every BVN belongs to the configured name, except
 * {@code 00000000000}, which does not exist.
 */
@Component
@ConditionalOnProperty(name = "paybridge.kyc.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedIdentityVerifier implements IdentityVerifier {

    static final String UNKNOWN_BVN = "00000000000";

    private final String firstName;
    private final String lastName;

    public SimulatedIdentityVerifier(@Value("${paybridge.kyc.simulated.first-name:Ada}") String firstName,
                                     @Value("${paybridge.kyc.simulated.last-name:Obi}") String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Override
    public BvnRecord lookupBvn(String bvn) {
        if (UNKNOWN_BVN.equals(bvn)) {
            throw new GatewayRejectedException("BVN could not be verified");
        }
        return new BvnRecord(firstName, null, lastName, "1990-01-01");
    }

    @Override
    public String providerName() {
        return "SIMULATED";
    }
}

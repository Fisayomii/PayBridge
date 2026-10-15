package com.academy.paybridge.customer.api;

import java.util.UUID;

public interface CustomerApi {
    CustomerDto getById(UUID customerId);

    /**
     * Same as {@link #getById} but rejects suspended customers (403). Money-moving flows call this on every
     * request, so a suspension takes effect immediately even while the customer's access token is still valid.
     */
    CustomerDto requireActive(UUID customerId);
}

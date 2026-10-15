package com.academy.paybridge.customer.api;

import com.academy.paybridge.shared.security.Role;

import java.time.Instant;
import java.util.UUID;

public record CustomerDto(
        UUID id,
        String email,
        String fullName,
        Role role,
        CustomerStatus status,
        /** CBN KYC tier (1-3); drives transaction and balance limits in the compliance module. */
        int kycTier,
        Instant createdAt
) {}

package com.academy.paybridge.shared.security;

import java.util.UUID;

/** The caller of a request, as proven by a valid access token. */
public record AuthenticatedUser(UUID customerId, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}

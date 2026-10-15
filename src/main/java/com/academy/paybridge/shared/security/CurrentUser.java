package com.academy.paybridge.shared.security;

import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/** Resolves the authenticated caller from the security context. Controllers pass it down explicitly. */
public final class CurrentUser {

    public static final String EMAIL_CLAIM = "email";
    public static final String ROLE_CLAIM = "role";

    private CurrentUser() {}

    public static AuthenticatedUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return fromJwt(token.getToken());
        }
        throw new PayBridgeException("Authentication required", HttpStatus.UNAUTHORIZED);
    }

    public static AuthenticatedUser fromJwt(Jwt jwt) {
        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(EMAIL_CLAIM),
                Role.valueOf(jwt.getClaimAsString(ROLE_CLAIM)));
    }
}

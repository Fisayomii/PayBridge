package com.academy.paybridge.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties(prefix = "paybridge.security.jwt")
public record JwtProperties(
        /** HMAC-SHA256 signing key. At least 32 bytes; supply via PAYBRIDGE_JWT_SECRET, never commit it. */
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "paybridge.security.jwt.secret (PAYBRIDGE_JWT_SECRET) must be set and at least 32 bytes long");
        }
        if (issuer == null || issuer.isBlank()) issuer = "paybridge";
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
        if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(14);
    }
}

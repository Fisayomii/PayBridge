package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.domain.RefreshToken;
import com.academy.paybridge.customer.repository.RefreshTokenRepository;
import com.academy.paybridge.shared.security.CurrentUser;
import com.academy.paybridge.shared.security.JwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final JwtProperties props;
    private final RefreshTokenRepository refreshTokenRepository;

    TokenService(JwtEncoder jwtEncoder, JwtProperties props, RefreshTokenRepository refreshTokenRepository) {
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Issues an access token and a refresh token. Pass the existing family when rotating. */
    IssuedTokens issue(Customer customer, UUID familyId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(customer.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(CurrentUser.EMAIL_CLAIM, customer.getEmail())
                .claim(CurrentUser.ROLE_CLAIM, customer.getRole().name())
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        refreshTokenRepository.save(new RefreshToken(customer.getId(),
                familyId != null ? familyId : UUID.randomUUID(),
                hash(refreshToken),
                now.plus(props.refreshTokenTtl())));

        return new IssuedTokens(accessToken, props.accessTokenTtl().toSeconds(), refreshToken);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record IssuedTokens(String accessToken, long expiresInSeconds, String refreshToken) {}
}

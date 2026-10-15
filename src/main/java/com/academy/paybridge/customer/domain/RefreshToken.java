package com.academy.paybridge.customer.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A single-use refresh token. Only its SHA-256 hash is stored. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    /** All tokens rotated from the same login share a family; reuse of an old one revokes the family. */
    @Column(nullable = false, updatable = false)
    private UUID familyId;

    @Column(nullable = false, unique = true, updatable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RefreshToken() {}

    public RefreshToken(UUID customerId, UUID familyId, String tokenHash, Instant expiresAt) {
        this.customerId = customerId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
}

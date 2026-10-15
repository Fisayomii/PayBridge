package com.academy.paybridge.customer.domain;

import com.academy.paybridge.customer.api.CustomerStatus;
import com.academy.paybridge.shared.security.Role;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Stored lower-cased; uniqueness enforced by uq_customers_email on lower(email). */
    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    @Column(nullable = false)
    private short kycTier = 1;

    @Column(nullable = false)
    private int failedLoginAttempts;

    private Instant lockedUntil;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected Customer() {}

    public Customer(String email, String fullName, String passwordHash, Role role) {
        this.email = email.trim().toLowerCase();
        this.fullName = fullName.trim();
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void recordFailedLogin(int maxAttempts, Duration lockDuration, Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginAttempts = 0;
        }
        touch();
    }

    public void recordSuccessfulLogin() {
        if (failedLoginAttempts != 0 || lockedUntil != null) {
            failedLoginAttempts = 0;
            lockedUntil = null;
            touch();
        }
    }

    public void changeKycTier(int tier) {
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("KYC tier must be 1, 2 or 3");
        }
        this.kycTier = (short) tier;
        touch();
    }

    public void changeStatus(CustomerStatus status) {
        this.status = status;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public CustomerStatus getStatus() { return status; }
    public int getKycTier() { return kycTier; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
}

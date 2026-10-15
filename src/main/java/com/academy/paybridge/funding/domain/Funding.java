package com.academy.paybridge.funding.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One movement of money into (or administratively out of) a wallet from outside the transfer system. */
@Entity
@Table(name = "fundings")
public class Funding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FundingChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FundingDirection direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FundingStatus status;

    @Column(length = 500)
    private String authorizationUrl;

    private String providerReference;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private UUID initiatedBy;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected Funding() {}

    public static Funding checkout(String reference, UUID accountId, BigDecimal amount, String currency,
                                   UUID initiatedBy, String idempotencyKey) {
        Funding f = new Funding();
        f.reference = reference;
        f.accountId = accountId;
        f.channel = FundingChannel.PAYSTACK_CHECKOUT;
        f.direction = FundingDirection.CREDIT;
        f.amount = amount;
        f.currency = currency;
        f.status = FundingStatus.PENDING;
        f.initiatedBy = initiatedBy;
        f.idempotencyKey = idempotencyKey;
        return f;
    }

    public static Funding adjustment(String reference, UUID accountId, FundingDirection direction, BigDecimal amount,
                                     String currency, String reason, UUID adminId, String idempotencyKey) {
        Funding f = new Funding();
        f.reference = reference;
        f.accountId = accountId;
        f.channel = FundingChannel.ADMIN_ADJUSTMENT;
        f.direction = direction;
        f.amount = amount;
        f.currency = currency;
        f.status = FundingStatus.SUCCESS;
        f.reason = reason;
        f.initiatedBy = adminId;
        f.idempotencyKey = idempotencyKey;
        f.completedAt = f.createdAt;
        return f;
    }

    public void attachCheckout(String authorizationUrl) {
        this.authorizationUrl = authorizationUrl;
        touch();
    }

    public void markSucceeded(String providerReference) {
        requirePending();
        this.status = FundingStatus.SUCCESS;
        this.providerReference = providerReference;
        complete();
    }

    public void markFailed(String reason) {
        requirePending();
        this.status = FundingStatus.FAILED;
        this.reason = truncate(reason);
        complete();
    }

    public void markAbandoned(String reason) {
        requirePending();
        this.status = FundingStatus.ABANDONED;
        this.reason = truncate(reason);
        complete();
    }

    private void requirePending() {
        if (status != FundingStatus.PENDING) {
            throw new IllegalStateException("Funding " + reference + " is already " + status);
        }
    }

    private void complete() {
        touch();
        this.completedAt = updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getAccountId() { return accountId; }
    public FundingChannel getChannel() { return channel; }
    public FundingDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public FundingStatus getStatus() { return status; }
    public String getAuthorizationUrl() { return authorizationUrl; }
    public String getProviderReference() { return providerReference; }
    public String getReason() { return reason; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}

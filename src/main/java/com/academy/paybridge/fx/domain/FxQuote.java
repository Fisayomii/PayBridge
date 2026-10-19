package com.academy.paybridge.fx.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_quotes")
public class FxQuote {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(nullable = false)
    private UUID partnerId;

    @Column(nullable = false, length = 3)
    private String sourceCurrency;

    @Column(nullable = false, length = 3)
    private String targetCurrency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal sourceAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal midRate;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected FxQuote() {}

    public FxQuote(String reference, UUID partnerId, BigDecimal sourceAmount, BigDecimal fee, BigDecimal midRate,
                   BigDecimal rate, BigDecimal targetAmount, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.partnerId = partnerId;
        this.sourceCurrency = "USD";
        this.targetCurrency = "NGN";
        this.sourceAmount = sourceAmount;
        this.fee = fee;
        this.midRate = midRate;
        this.rate = rate;
        this.targetAmount = targetAmount;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getPartnerId() { return partnerId; }
    public String getSourceCurrency() { return sourceCurrency; }
    public String getTargetCurrency() { return targetCurrency; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getMidRate() { return midRate; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

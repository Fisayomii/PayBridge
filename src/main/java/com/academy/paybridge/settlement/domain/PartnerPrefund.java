package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A partner's wire that has landed in our USD bank account. */
@Entity
@Table(name = "partner_prefunds")
public class PartnerPrefund {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(nullable = false)
    private UUID partnerAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, unique = true, length = 100)
    private String bankReference;

    @Column(nullable = false)
    private UUID recordedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PartnerPrefund() {}

    public PartnerPrefund(String reference, UUID partnerAccountId, BigDecimal amount, String currency,
                          String bankReference, UUID recordedBy) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.partnerAccountId = partnerAccountId;
        this.amount = amount;
        this.currency = currency;
        this.bankReference = bankReference;
        this.recordedBy = recordedBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getPartnerAccountId() { return partnerAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getBankReference() { return bankReference; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}

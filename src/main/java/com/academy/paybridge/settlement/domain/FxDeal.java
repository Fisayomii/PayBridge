package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One conversion of the whole open USD position at the dealer bank. */
@Entity
@Table(name = "fx_deals")
public class FxDeal {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(nullable = false, unique = true, length = 100)
    private String bankReference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal usdAmount;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal executedRate;

    /** NGN the bank gave us for usdAmount. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal ngnAmount;

    /** NGN we had already paid recipients for that USD at quoted rates. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal ngnPaidOut;

    /** ngnAmount - ngnPaidOut: positive is a gain. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal realisedPnl;

    @Column(nullable = false)
    private UUID bookedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected FxDeal() {}

    public FxDeal(String reference, String bankReference, BigDecimal usdAmount, BigDecimal executedRate,
                  BigDecimal ngnAmount, BigDecimal ngnPaidOut, UUID bookedBy) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.bankReference = bankReference;
        this.usdAmount = usdAmount;
        this.executedRate = executedRate;
        this.ngnAmount = ngnAmount;
        this.ngnPaidOut = ngnPaidOut;
        this.realisedPnl = ngnAmount.subtract(ngnPaidOut);
        this.bookedBy = bookedBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public String getBankReference() { return bankReference; }
    public BigDecimal getUsdAmount() { return usdAmount; }
    public BigDecimal getExecutedRate() { return executedRate; }
    public BigDecimal getNgnAmount() { return ngnAmount; }
    public BigDecimal getNgnPaidOut() { return ngnPaidOut; }
    public BigDecimal getRealisedPnl() { return realisedPnl; }
    public UUID getBookedBy() { return bookedBy; }
    public Instant getCreatedAt() { return createdAt; }
}

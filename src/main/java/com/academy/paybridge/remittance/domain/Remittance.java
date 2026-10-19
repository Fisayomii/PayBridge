package com.academy.paybridge.remittance.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "remittances")
public class Remittance {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(nullable = false)
    private UUID partnerId;

    @Column(nullable = false)
    private UUID partnerAccountId;

    @Column(nullable = false, unique = true)
    private UUID quoteId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(length = 100)
    private String partnerReference;

    @Column(nullable = false)
    private String senderName;

    @Column(nullable = false, length = 2)
    private String senderCountry;

    @Column(nullable = false, length = 100)
    private String senderIdNumber;

    @Column(nullable = false, length = 500)
    private String senderAddress;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false, length = 50)
    private String recipientWalletNumber;

    @Column(nullable = false)
    private UUID recipientAccountId;

    @Column(nullable = false, length = 100)
    private String purpose;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal sourceAmount;

    @Column(nullable = false, length = 3)
    private String sourceCurrency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;

    @Column(nullable = false, length = 3)
    private String targetCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RemittanceStatus status;

    @Column(length = 1000)
    private String screeningNotes;

    @Column(length = 500)
    private String reviewNote;

    private UUID reviewedBy;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected Remittance() {}

    public Remittance(String reference, UUID partnerId, UUID partnerAccountId, UUID quoteId, String idempotencyKey,
                      String requestFingerprint, String partnerReference, String senderName, String senderCountry,
                      String senderIdNumber, String senderAddress, String recipientName,
                      String recipientWalletNumber, UUID recipientAccountId, String purpose,
                      BigDecimal sourceAmount, BigDecimal fee, BigDecimal rate, BigDecimal targetAmount) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.partnerId = partnerId;
        this.partnerAccountId = partnerAccountId;
        this.quoteId = quoteId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.partnerReference = partnerReference;
        this.senderName = senderName;
        this.senderCountry = senderCountry;
        this.senderIdNumber = senderIdNumber;
        this.senderAddress = senderAddress;
        this.recipientName = recipientName;
        this.recipientWalletNumber = recipientWalletNumber;
        this.recipientAccountId = recipientAccountId;
        this.purpose = purpose;
        this.sourceAmount = sourceAmount;
        this.sourceCurrency = "USD";
        this.fee = fee;
        this.rate = rate;
        this.targetAmount = targetAmount;
        this.targetCurrency = "NGN";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void holdForReview(List<String> reasons) {
        this.status = RemittanceStatus.PENDING_REVIEW;
        this.screeningNotes = joined(reasons);
        touch();
    }

    public void complete(UUID reviewer, String note) {
        this.status = RemittanceStatus.COMPLETED;
        this.reviewedBy = reviewer;
        this.reviewNote = note;
        this.completedAt = touch();
    }

    public void reject(List<String> reasons, UUID reviewer, String note) {
        this.status = RemittanceStatus.REJECTED;
        if (reasons != null && !reasons.isEmpty()) this.screeningNotes = joined(reasons);
        this.reviewedBy = reviewer;
        this.reviewNote = note;
        this.completedAt = touch();
    }

    public void fail(String reason, UUID reviewer, String note) {
        this.status = RemittanceStatus.FAILED;
        this.failureReason = reason;
        this.reviewedBy = reviewer;
        this.reviewNote = note;
        this.completedAt = touch();
    }

    public BigDecimal totalCharged() {
        return sourceAmount.add(fee);
    }

    private Instant touch() {
        this.updatedAt = Instant.now();
        return updatedAt;
    }

    private static String joined(List<String> reasons) {
        String s = String.join("; ", reasons);
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getPartnerId() { return partnerId; }
    public UUID getPartnerAccountId() { return partnerAccountId; }
    public UUID getQuoteId() { return quoteId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getPartnerReference() { return partnerReference; }
    public String getSenderName() { return senderName; }
    public String getSenderCountry() { return senderCountry; }
    public String getSenderIdNumber() { return senderIdNumber; }
    public String getSenderAddress() { return senderAddress; }
    public String getRecipientName() { return recipientName; }
    public String getRecipientWalletNumber() { return recipientWalletNumber; }
    public UUID getRecipientAccountId() { return recipientAccountId; }
    public String getPurpose() { return purpose; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
    public String getSourceCurrency() { return sourceCurrency; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public String getTargetCurrency() { return targetCurrency; }
    public RemittanceStatus getStatus() { return status; }
    public String getScreeningNotes() { return screeningNotes; }
    public String getReviewNote() { return reviewNote; }
    public UUID getReviewedBy() { return reviewedBy; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}

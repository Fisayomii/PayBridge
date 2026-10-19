package com.academy.paybridge.customer.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One identity check attempt. Kept for audit whether it passed or not. */
@Entity
@Table(name = "kyc_verifications")
public class KycVerification {

    public enum Status { VERIFIED, FAILED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 10)
    private String idType;

    @Column(name = "id_number_mask", nullable = false, length = 20)
    private String idNumberMask;

    @Column(name = "id_number_hash", nullable = false, length = 64)
    private String idNumberHash;

    @Column(nullable = false, length = 20)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 300)
    private String failureReason;

    @Column(nullable = false)
    private short tierBefore;

    @Column(nullable = false)
    private short tierAfter;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected KycVerification() {}

    public KycVerification(UUID customerId, String idType, String idNumberMask, String idNumberHash, String provider,
                           Status status, String failureReason, int tierBefore, int tierAfter) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.idType = idType;
        this.idNumberMask = idNumberMask;
        this.idNumberHash = idNumberHash;
        this.provider = provider;
        this.status = status;
        this.failureReason = failureReason;
        this.tierBefore = (short) tierBefore;
        this.tierAfter = (short) tierAfter;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getIdType() { return idType; }
    public String getIdNumberMask() { return idNumberMask; }
    public String getIdNumberHash() { return idNumberHash; }
    public String getProvider() { return provider; }
    public Status getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public int getTierBefore() { return tierBefore; }
    public int getTierAfter() { return tierAfter; }
    public Instant getCreatedAt() { return createdAt; }
}

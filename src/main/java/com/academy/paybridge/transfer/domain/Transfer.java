package com.academy.paybridge.transfer.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String reference;

    /** Unique per source account, not globally (see uq_transfers_source_idempotency). */
    @Column(nullable = false)
    private String idempotencyKey;

    /** SHA-256 of the request, so a reused idempotency key with a different payload is rejected. */
    @Column(length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, length = 20)
    private TransferType type;

    @Column(nullable = false)
    private UUID sourceAccountId;

    @Column(nullable = false)
    private String sourceWalletNumber;

    /** INTERNAL only. */
    private UUID destinationAccountId;

    /** INTERNAL only. */
    private String destinationWalletNumber;

    /** EXTERNAL only. */
    @Column(length = 20)
    private String beneficiaryAccountNumber;

    /** EXTERNAL only. */
    @Column(length = 10)
    private String beneficiaryBankCode;

    @Column(nullable = false)
    private String resolvedBeneficiaryName;

    private String recipientCode;

    private String gatewayTransferCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Charged on top of {@link #amount}; refunded with it if a payout fails or is reversed. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee = BigDecimal.ZERO.setScale(2);

    /** Customer who instructed the transfer (null for transfers made before authentication existed). */
    private UUID initiatedBy;

    /** Saved beneficiary used, if any. */
    private UUID beneficiaryId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(nullable = false)
    private String narration;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected Transfer() {}

    private Transfer(TransferType type, String reference, String idempotencyKey, String requestFingerprint,
                     UUID initiatedBy, UUID sourceAccountId, String sourceWalletNumber, BigDecimal amount,
                     BigDecimal fee, String currency, String narration, TransferStatus status,
                     String resolvedBeneficiaryName, UUID beneficiaryId) {
        this.type = type;
        this.initiatedBy = initiatedBy;
        this.fee = fee;
        this.beneficiaryId = beneficiaryId;
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.sourceAccountId = sourceAccountId;
        this.sourceWalletNumber = sourceWalletNumber;
        this.amount = amount;
        this.currency = currency;
        this.narration = narration;
        this.status = status;
        this.resolvedBeneficiaryName = resolvedBeneficiaryName;
        if (status.isFinal()) {
            this.completedAt = this.createdAt;
        }
    }

    public static Transfer internal(String reference, String idempotencyKey, String requestFingerprint,
                                    UUID initiatedBy, UUID sourceAccountId, String sourceWalletNumber,
                                    UUID destinationAccountId, String destinationWalletNumber,
                                    String beneficiaryName, UUID beneficiaryId,
                                    BigDecimal amount, BigDecimal fee, String currency, String narration) {
        Transfer t = new Transfer(TransferType.INTERNAL, reference, idempotencyKey, requestFingerprint,
                initiatedBy, sourceAccountId, sourceWalletNumber, amount, fee, currency, narration,
                TransferStatus.SUCCESS, beneficiaryName, beneficiaryId);
        t.destinationAccountId = destinationAccountId;
        t.destinationWalletNumber = destinationWalletNumber;
        return t;
    }

    public static Transfer externalPending(String reference, String idempotencyKey, String requestFingerprint,
                                           UUID initiatedBy, UUID sourceAccountId, String sourceWalletNumber,
                                           String beneficiaryAccountNumber, String beneficiaryBankCode,
                                           String resolvedBeneficiaryName, String recipientCode, UUID beneficiaryId,
                                           BigDecimal amount, BigDecimal fee, String currency, String narration) {
        Transfer t = new Transfer(TransferType.EXTERNAL, reference, idempotencyKey, requestFingerprint,
                initiatedBy, sourceAccountId, sourceWalletNumber, amount, fee, currency, narration,
                TransferStatus.PENDING, resolvedBeneficiaryName, beneficiaryId);
        t.beneficiaryAccountNumber = beneficiaryAccountNumber;
        t.beneficiaryBankCode = beneficiaryBankCode;
        t.recipientCode = recipientCode;
        return t;
    }

    /** Provider accepted the payout but has not settled it yet. */
    public void markProcessing(String gatewayTransferCode) {
        requireStatus(TransferStatus.PENDING);
        this.status = TransferStatus.PROCESSING;
        recordGatewayCode(gatewayTransferCode);
        touch();
    }

    public void markSucceeded(String gatewayTransferCode) {
        requireStatus(TransferStatus.PENDING, TransferStatus.PROCESSING);
        this.status = TransferStatus.SUCCESS;
        recordGatewayCode(gatewayTransferCode);
        complete();
    }

    public void markFailed(String reason) {
        requireStatus(TransferStatus.PENDING, TransferStatus.PROCESSING);
        this.status = TransferStatus.FAILED;
        this.failureReason = truncate(reason);
        complete();
    }

    public void markReversed(String reason) {
        requireStatus(TransferStatus.PENDING, TransferStatus.PROCESSING, TransferStatus.SUCCESS);
        this.status = TransferStatus.REVERSED;
        this.failureReason = truncate(reason);
        complete();
    }

    private void recordGatewayCode(String code) {
        if (code != null && !code.isBlank()) {
            this.gatewayTransferCode = code;
        }
    }

    private void requireStatus(TransferStatus... allowed) {
        for (TransferStatus s : allowed) {
            if (status == s) return;
        }
        throw new IllegalStateException("Transfer " + reference + " cannot move from " + status);
    }

    private void complete() {
        touch();
        this.completedAt = this.updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public TransferType getType() { return type; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public String getSourceWalletNumber() { return sourceWalletNumber; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public String getDestinationWalletNumber() { return destinationWalletNumber; }
    public String getBeneficiaryAccountNumber() { return beneficiaryAccountNumber; }
    public String getBeneficiaryBankCode() { return beneficiaryBankCode; }
    public String getResolvedBeneficiaryName() { return resolvedBeneficiaryName; }
    public String getRecipientCode() { return recipientCode; }
    public String getGatewayTransferCode() { return gatewayTransferCode; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getFee() { return fee; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public UUID getBeneficiaryId() { return beneficiaryId; }
    public String getCurrency() { return currency; }
    public TransferStatus getStatus() { return status; }
    public String getNarration() { return narration; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}

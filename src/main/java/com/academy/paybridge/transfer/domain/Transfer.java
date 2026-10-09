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

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private UUID sourceAccountId;

    @Column(nullable = false)
    private String sourceWalletNumber;

    @Column(nullable = false)
    private UUID destinationAccountId;

    @Column(nullable = false)
    private String destinationWalletNumber;

    @Column(nullable = false)
    private String resolvedBeneficiaryName;

    @Column(nullable = false)
    private String recipientCode;

    @Column(nullable = false)
    private String gatewayTransferCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(nullable = false)
    private String narration;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Transfer() {}

    public Transfer(String reference, String idempotencyKey, UUID sourceAccountId, String sourceWalletNumber,
                    UUID destinationAccountId, String destinationWalletNumber, String resolvedBeneficiaryName,
                    String recipientCode, String gatewayTransferCode, BigDecimal amount, String currency,
                    TransferStatus status, String narration) {
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.sourceAccountId = sourceAccountId;
        this.sourceWalletNumber = sourceWalletNumber;
        this.destinationAccountId = destinationAccountId;
        this.destinationWalletNumber = destinationWalletNumber;
        this.resolvedBeneficiaryName = resolvedBeneficiaryName;
        this.recipientCode = recipientCode;
        this.gatewayTransferCode = gatewayTransferCode;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.narration = narration;
    }

    public void markStatus(TransferStatus newStatus) {
        this.status = newStatus;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public String getSourceWalletNumber() { return sourceWalletNumber; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public String getDestinationWalletNumber() { return destinationWalletNumber; }
    public String getResolvedBeneficiaryName() { return resolvedBeneficiaryName; }
    public String getRecipientCode() { return recipientCode; }
    public String getGatewayTransferCode() { return gatewayTransferCode; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public TransferStatus getStatus() { return status; }
    public String getNarration() { return narration; }
    public Instant getCreatedAt() { return createdAt; }
}
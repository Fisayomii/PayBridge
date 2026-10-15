package com.academy.paybridge.transfer.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer's saved payee. External beneficiaries keep the provider recipient code, so repeat payouts
 * skip name enquiry and recipient creation.
 */
@Entity
@Table(name = "beneficiaries")
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TransferType type;

    @Column(length = 100)
    private String nickname;

    private String walletNumber;

    @Column(length = 20)
    private String accountNumber;

    @Column(length = 10)
    private String bankCode;

    @Column(nullable = false)
    private String resolvedName;

    private String recipientCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Beneficiary() {}

    public static Beneficiary internal(UUID customerId, String walletNumber, String resolvedName, String nickname) {
        Beneficiary b = new Beneficiary();
        b.customerId = customerId;
        b.type = TransferType.INTERNAL;
        b.walletNumber = walletNumber;
        b.resolvedName = resolvedName;
        b.nickname = nickname;
        return b;
    }

    public static Beneficiary external(UUID customerId, String accountNumber, String bankCode, String resolvedName,
                                       String recipientCode, String nickname) {
        Beneficiary b = new Beneficiary();
        b.customerId = customerId;
        b.type = TransferType.EXTERNAL;
        b.accountNumber = accountNumber;
        b.bankCode = bankCode;
        b.resolvedName = resolvedName;
        b.recipientCode = recipientCode;
        b.nickname = nickname;
        return b;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public TransferType getType() { return type; }
    public String getNickname() { return nickname; }
    public String getWalletNumber() { return walletNumber; }
    public String getAccountNumber() { return accountNumber; }
    public String getBankCode() { return bankCode; }
    public String getResolvedName() { return resolvedName; }
    public String getRecipientCode() { return recipientCode; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.academy.paybridge.ledger.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String transactionReference;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 10)
    private String entryType; // DEBIT or CREDIT

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String narration;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {}

    public LedgerEntry(String transactionReference, UUID accountId, String entryType,
                       BigDecimal amount, String currency, String narration) {
        this.transactionReference = transactionReference;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.narration = narration;
    }

    public UUID getId() { return id; }
    public String getTransactionReference() { return transactionReference; }
    public UUID getAccountId() { return accountId; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getNarration() { return narration; }
    public Instant getCreatedAt() { return createdAt; }
}
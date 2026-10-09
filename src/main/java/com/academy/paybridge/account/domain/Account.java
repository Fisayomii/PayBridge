package com.academy.paybridge.account.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String walletNumber;

    @Column(nullable = false)
    private String ownerName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String externalBankAccountNumber;

    @Column(nullable = false)
    private String externalBankCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected Account() {}

    // 7-argument constructor (with email)
    public Account(String walletNumber, String ownerName, String email,
                   String externalBankAccountNumber, String externalBankCode,
                   BigDecimal balance, String currency) {
        this.walletNumber = walletNumber;
        this.ownerName = ownerName;
        this.email = email;
        this.externalBankAccountNumber = externalBankAccountNumber;
        this.externalBankCode = externalBankCode;
        this.balance = balance;
        this.currency = currency.toUpperCase();
        this.version = 0L;
    }

    // 6-argument constructor (also supported so nothing ever breaks)
    public Account(String walletNumber, String ownerName,
                   String externalBankAccountNumber, String externalBankCode,
                   BigDecimal balance, String currency) {
        this(walletNumber, ownerName, "alerts@paybridge.local",
                externalBankAccountNumber, externalBankCode, balance, currency);
    }

    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public UUID getId() { return id; }
    public String getWalletNumber() { return walletNumber; }
    public String getOwnerName() { return ownerName; }
    public String getEmail() { return email; }
    public String getExternalBankAccountNumber() { return externalBankAccountNumber; }
    public String getExternalBankCode() { return externalBankCode; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
}
package com.academy.paybridge.account.domain;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountType;
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

    /** Owning customer. Null for INTERNAL GL accounts. */
    private UUID customerId;

    @Column(nullable = false)
    private String ownerName;

    @Column(nullable = false)
    private String email;

    /** Legacy columns from the first prototype; no longer written. */
    private String externalBankAccountNumber;
    private String externalBankCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType type = AccountType.CUSTOMER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected Account() {}

    public static Account customerWallet(String walletNumber, UUID customerId, String ownerName,
                                         String email, String currency) {
        Account a = new Account();
        a.walletNumber = walletNumber;
        a.customerId = customerId;
        a.ownerName = ownerName;
        a.email = email;
        a.balance = BigDecimal.ZERO.setScale(2);
        a.currency = currency.toUpperCase();
        a.type = AccountType.CUSTOMER;
        return a;
    }

    public static Account partnerPrefund(String walletNumber, UUID partnerId, String partnerName,
                                         String email, String currency) {
        Account a = customerWallet(walletNumber, partnerId, partnerName, email, currency);
        a.type = AccountType.PARTNER;
        return a;
    }

    public void debit(BigDecimal amount) {
        if (type != AccountType.INTERNAL && balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Debit would overdraw account " + walletNumber);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public void changeStatus(AccountStatus newStatus) {
        this.status = newStatus;
    }

    public UUID getId() { return id; }
    public String getWalletNumber() { return walletNumber; }
    public UUID getCustomerId() { return customerId; }
    public String getOwnerName() { return ownerName; }
    public String getEmail() { return email; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public AccountType getType() { return type; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}

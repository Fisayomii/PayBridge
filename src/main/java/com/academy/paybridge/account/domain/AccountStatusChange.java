package com.academy.paybridge.account.domain;

import com.academy.paybridge.account.api.AccountStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit record of who froze / unfroze a wallet, when, and why. */
@Entity
@Table(name = "account_status_history")
public class AccountStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AccountStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AccountStatus newStatus;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(nullable = false, updatable = false)
    private UUID changedBy;

    @Column(nullable = false, updatable = false)
    private Instant changedAt = Instant.now();

    protected AccountStatusChange() {}

    public AccountStatusChange(UUID accountId, AccountStatus oldStatus, AccountStatus newStatus,
                               String reason, UUID changedBy) {
        this.accountId = accountId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.changedBy = changedBy;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public AccountStatus getOldStatus() { return oldStatus; }
    public AccountStatus getNewStatus() { return newStatus; }
    public String getReason() { return reason; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}

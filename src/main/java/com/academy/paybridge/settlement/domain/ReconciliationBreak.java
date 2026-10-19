package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A disagreement between our ledger and the provider that someone has to look at. */
@Entity
@Table(name = "reconciliation_breaks")
public class ReconciliationBreak {

    public enum Type {
        /** Both sides know the payout but disagree on its outcome (often a lost webhook). */
        STATUS_MISMATCH,
        /** Our ledger moved money for a payout the provider has no record of. */
        MISSING_AT_PROVIDER
    }

    public enum Status { OPEN, RESOLVED }

    public enum Resolution {
        /** Apply the provider's status through the normal payout state machine (refunds included). */
        ACCEPT_PROVIDER,
        /** Investigated and closed with a note; no money moved. */
        CLOSED_WITH_NOTE
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type breakType;

    @Column(nullable = false, length = 64)
    private String reference;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 20)
    private String ledgerStatus;

    @Column(length = 20)
    private String providerStatus;

    @Column(length = 500)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Resolution resolution;

    @Column(length = 500)
    private String resolutionNote;

    private UUID resolvedBy;

    private Instant resolvedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationBreak() {}

    public ReconciliationBreak(UUID runId, Type breakType, String reference, BigDecimal amount,
                               String ledgerStatus, String providerStatus, String details) {
        this.id = UUID.randomUUID();
        this.runId = runId;
        this.breakType = breakType;
        this.reference = reference;
        this.amount = amount;
        this.ledgerStatus = ledgerStatus;
        this.providerStatus = providerStatus;
        this.details = details != null && details.length() > 500 ? details.substring(0, 500) : details;
        this.status = Status.OPEN;
        this.createdAt = Instant.now();
    }

    public void resolve(Resolution resolution, String note, UUID by) {
        this.status = Status.RESOLVED;
        this.resolution = resolution;
        this.resolutionNote = note;
        this.resolvedBy = by;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public Type getBreakType() { return breakType; }
    public String getReference() { return reference; }
    public BigDecimal getAmount() { return amount; }
    public String getLedgerStatus() { return ledgerStatus; }
    public String getProviderStatus() { return providerStatus; }
    public String getDetails() { return details; }
    public Status getStatus() { return status; }
    public Resolution getResolution() { return resolution; }
    public String getResolutionNote() { return resolutionNote; }
    public UUID getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

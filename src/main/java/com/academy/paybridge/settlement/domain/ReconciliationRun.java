package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public static final String PAYOUTS = "PAYOUTS";

    @Id
    private UUID id;

    @Column(nullable = false, length = 30)
    private String runType;

    @Column(nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private int itemsChecked;

    @Column(nullable = false)
    private int breaksFound;

    /** Null when the nightly job ran it. */
    private UUID triggeredBy;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant finishedAt;

    protected ReconciliationRun() {}

    public ReconciliationRun(String runType, LocalDate businessDate, UUID triggeredBy, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.runType = runType;
        this.businessDate = businessDate;
        this.triggeredBy = triggeredBy;
        this.startedAt = startedAt;
        this.finishedAt = startedAt;
    }

    public void finish(int itemsChecked, int breaksFound) {
        this.itemsChecked = itemsChecked;
        this.breaksFound = breaksFound;
        this.finishedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getRunType() { return runType; }
    public LocalDate getBusinessDate() { return businessDate; }
    public int getItemsChecked() { return itemsChecked; }
    public int getBreaksFound() { return breaksFound; }
    public UUID getTriggeredBy() { return triggeredBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}

package com.academy.paybridge.fx.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One published mid rate. Rows are never updated, so the history doubles as an audit trail. */
@Entity
@Table(name = "fx_rates")
public class FxRate {

    public static final String USD_NGN = "USD/NGN";

    @Id
    private UUID id;

    @Column(nullable = false, length = 7)
    private String pair;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal midRate;

    @Column(nullable = false, length = 50)
    private String source;

    private UUID setBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected FxRate() {}

    public FxRate(String pair, BigDecimal midRate, String source, UUID setBy) {
        this.id = UUID.randomUUID();
        this.pair = pair;
        this.midRate = midRate;
        this.source = source;
        this.setBy = setBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPair() { return pair; }
    public BigDecimal getMidRate() { return midRate; }
    public String getSource() { return source; }
    public UUID getSetBy() { return setBy; }
    public Instant getCreatedAt() { return createdAt; }
}

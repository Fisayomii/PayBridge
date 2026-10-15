package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.domain.TransferType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Customer-facing transfer fees, configured under {@code paybridge.transfer.fees}.
 * External payouts are banded by amount (the last band has no upper bound); internal transfers have a flat fee.
 */
@Component
@EnableConfigurationProperties(FeeSchedule.FeeProperties.class)
public class FeeSchedule {

    private final FeeProperties props;

    public FeeSchedule(FeeProperties props) {
        if (props.externalBands() == null || props.externalBands().isEmpty()) {
            throw new IllegalStateException("paybridge.transfer.fees.external-bands must define at least one band");
        }
        this.props = props;
    }

    public BigDecimal feeFor(TransferType type, BigDecimal amount) {
        if (type == TransferType.INTERNAL) {
            return scale(props.internal() == null ? BigDecimal.ZERO : props.internal());
        }
        for (Band band : props.externalBands()) {
            if (band.upTo() == null || amount.compareTo(band.upTo()) <= 0) {
                return scale(band.fee());
            }
        }
        return scale(props.externalBands().getLast().fee());
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }

    @ConfigurationProperties(prefix = "paybridge.transfer.fees")
    public record FeeProperties(BigDecimal internal, List<Band> externalBands) {}

    public record Band(BigDecimal upTo, BigDecimal fee) {}
}

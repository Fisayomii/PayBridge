package com.academy.paybridge.remittance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Per-remittance bounds in USD. Daily per-partner caps are left for later (see README). */
@ConfigurationProperties(prefix = "paybridge.remittance")
public record RemittanceProperties(BigDecimal minAmountUsd, BigDecimal maxAmountUsd) {}

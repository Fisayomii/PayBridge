package com.academy.paybridge.account.api;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDto(
        UUID id,
        String walletNumber,
        String ownerName,
        String email,
        String externalBankAccountNumber,
        String externalBankCode,
        BigDecimal balance,
        String currency
) {}
package com.academy.paybridge.account.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface AccountApi {
    AccountDto getByWalletNumber(String walletNumber);
    AccountDto getById(UUID accountId);
    void debit(UUID accountId, BigDecimal amount, String currency);
    void credit(UUID accountId, BigDecimal amount, String currency);
}
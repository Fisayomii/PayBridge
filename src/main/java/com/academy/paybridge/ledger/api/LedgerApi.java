package com.academy.paybridge.ledger.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerApi {
    void recordDoubleEntry(String reference, UUID debitAccountId, UUID creditAccountId,
                           BigDecimal amount, String currency, String narration);
    List<LedgerEntryDto> getEntriesByReference(String reference);
}
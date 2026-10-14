package com.academy.paybridge.ledger.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerApi {
    void recordDoubleEntry(String reference, UUID debitAccountId, UUID creditAccountId,
                           BigDecimal amount, String currency, String narration);
    List<LedgerEntryDto> getEntriesByReference(String reference);
    List<LedgerEntryDto> getEntriesByReferences(Collection<String> references);

    /** Account statement, newest first. */
    List<LedgerEntryDto> getEntriesForAccount(UUID accountId, int page, int size);
}
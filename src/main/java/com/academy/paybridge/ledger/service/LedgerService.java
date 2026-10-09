package com.academy.paybridge.ledger.service;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryDto;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerService implements LedgerApi {

    private final LedgerEntryRepository repository;

    public LedgerService(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void recordDoubleEntry(String reference, UUID debitAccountId, UUID creditAccountId,
                                  BigDecimal amount, String currency, String narration) {
        LedgerEntry debit = new LedgerEntry(reference, debitAccountId, "DEBIT", amount, currency, narration);
        LedgerEntry credit = new LedgerEntry(reference, creditAccountId, "CREDIT", amount, currency, narration);
        repository.saveAll(List.of(debit, credit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntryDto> getEntriesByReference(String reference) {
        return repository.findByTransactionReferenceOrderByCreatedAtAsc(reference)
                .stream()
                .map(e -> new LedgerEntryDto(
                        e.getId(),
                        e.getTransactionReference(),
                        e.getAccountId(),
                        e.getEntryType(),
                        e.getAmount(),
                        e.getCurrency(),
                        e.getNarration(),
                        e.getCreatedAt()
                ))
                .toList();
    }
}
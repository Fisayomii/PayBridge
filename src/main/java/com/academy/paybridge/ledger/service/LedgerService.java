package com.academy.paybridge.ledger.service;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryDto;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
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
                .map(LedgerService::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntryDto> getEntriesByReferences(Collection<String> references) {
        return repository.findByTransactionReferenceInOrderByCreatedAtAsc(references)
                .stream()
                .map(LedgerService::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntryDto> getEntriesForAccount(UUID accountId, int page, int size) {
        return repository.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, Math.min(size, 200)))
                .stream()
                .map(LedgerService::toDto)
                .toList();
    }

    private static LedgerEntryDto toDto(LedgerEntry e) {
        return new LedgerEntryDto(
                e.getId(),
                e.getTransactionReference(),
                e.getAccountId(),
                e.getEntryType(),
                e.getAmount(),
                e.getCurrency(),
                e.getNarration(),
                e.getCreatedAt()
        );
    }
}
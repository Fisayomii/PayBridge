package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByTransactionReferenceOrderByCreatedAtAsc(String transactionReference);
    List<LedgerEntry> findByTransactionReferenceInOrderByCreatedAtAsc(Collection<String> transactionReferences);
    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);
}
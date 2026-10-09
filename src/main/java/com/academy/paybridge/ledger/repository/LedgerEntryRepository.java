package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByTransactionReferenceOrderByCreatedAtAsc(String transactionReference);
}
package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.ReconciliationBreak;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationBreakRepository extends JpaRepository<ReconciliationBreak, UUID> {
    boolean existsByReferenceAndStatus(String reference, ReconciliationBreak.Status status);

    List<ReconciliationBreak> findByStatusOrderByCreatedAtAsc(ReconciliationBreak.Status status);

    List<ReconciliationBreak> findAllByOrderByCreatedAtDesc();
}

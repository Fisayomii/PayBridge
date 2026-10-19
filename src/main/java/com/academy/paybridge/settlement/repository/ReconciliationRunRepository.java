package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.ReconciliationRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
    List<ReconciliationRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}

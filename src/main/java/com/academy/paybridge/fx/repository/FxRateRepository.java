package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.FxRate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {
    Optional<FxRate> findFirstByPairOrderByCreatedAtDesc(String pair);

    List<FxRate> findByPairOrderByCreatedAtDesc(String pair, Pageable pageable);
}

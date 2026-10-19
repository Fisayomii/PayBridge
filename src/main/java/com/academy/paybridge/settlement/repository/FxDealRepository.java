package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.FxDeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FxDealRepository extends JpaRepository<FxDeal, UUID> {
    Optional<FxDeal> findByBankReference(String bankReference);

    List<FxDeal> findAllByOrderByCreatedAtDesc();
}

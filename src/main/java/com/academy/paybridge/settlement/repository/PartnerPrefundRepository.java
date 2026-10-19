package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.PartnerPrefund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerPrefundRepository extends JpaRepository<PartnerPrefund, UUID> {
    Optional<PartnerPrefund> findByBankReference(String bankReference);

    List<PartnerPrefund> findAllByOrderByCreatedAtDesc();
}

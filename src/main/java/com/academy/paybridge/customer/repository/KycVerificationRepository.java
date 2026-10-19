package com.academy.paybridge.customer.repository;

import com.academy.paybridge.customer.domain.KycVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface KycVerificationRepository extends JpaRepository<KycVerification, UUID> {

    List<KycVerification> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    long countByCustomerIdAndStatusAndCreatedAtAfter(UUID customerId, KycVerification.Status status, Instant since);

    boolean existsByIdTypeAndIdNumberHashAndStatus(String idType, String idNumberHash, KycVerification.Status status);

    boolean existsByCustomerIdAndIdTypeAndStatus(UUID customerId, String idType, KycVerification.Status status);
}

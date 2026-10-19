package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface RemittanceRepository extends JpaRepository<Remittance, UUID> {

    Optional<Remittance> findByReference(String reference);

    Optional<Remittance> findByPartnerIdAndIdempotencyKey(UUID partnerId, String idempotencyKey);

    /** Serialises an approval and a rejection clicked at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Remittance r where r.reference = :reference")
    Optional<Remittance> lockByReference(@Param("reference") String reference);

    Page<Remittance> findByPartnerIdOrderByCreatedAtDesc(UUID partnerId, Pageable pageable);

    Page<Remittance> findByStatusOrderByCreatedAtAsc(RemittanceStatus status, Pageable pageable);

    Page<Remittance> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select coalesce(sum(r.sourceAmount + r.fee), 0) from Remittance r where r.status = :status")
    BigDecimal sumChargedByStatus(@Param("status") RemittanceStatus status);
}

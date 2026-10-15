package com.academy.paybridge.funding.repository;

import com.academy.paybridge.funding.domain.Funding;
import com.academy.paybridge.funding.domain.FundingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FundingRepository extends JpaRepository<Funding, UUID> {

    Optional<Funding> findByReference(String reference);

    Optional<Funding> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Funding f where f.reference = :reference")
    Optional<Funding> lockByReference(@Param("reference") String reference);

    List<Funding> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    List<Funding> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(FundingStatus status, Instant before, Pageable pageable);
}

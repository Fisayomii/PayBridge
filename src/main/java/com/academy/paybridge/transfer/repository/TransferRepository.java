package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.domain.TransferType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
    Optional<Transfer> findByReference(String reference);

    Optional<Transfer> findBySourceAccountIdAndIdempotencyKey(UUID sourceAccountId, String idempotencyKey);

    /** Serialises webhook, reconciler and initiation updates on the same transfer. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.reference = :reference")
    Optional<Transfer> lockByReference(@Param("reference") String reference);

    List<Transfer> findByTypeAndStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
            TransferType type, Collection<TransferStatus> statuses, Instant createdBefore, Pageable pageable);

    Page<Transfer> findBySourceAccountIdInOrderByCreatedAtDesc(Collection<UUID> sourceAccountIds, Pageable pageable);

    /** Money that has left (or is leaving) the wallet since {@code since}; failed/reversed transfers do not count. */
    @Query("""
            select coalesce(sum(t.amount), 0) from Transfer t
            where t.sourceAccountId = :accountId and t.createdAt >= :since and t.status not in :excluded""")
    BigDecimal sumOutgoingSince(@Param("accountId") UUID accountId,
                                @Param("since") Instant since,
                                @Param("excluded") Collection<TransferStatus> excluded);

    long countBySourceAccountIdAndCreatedAtAfter(UUID sourceAccountId, Instant since);

    List<Transfer> findByTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            TransferType type, Instant from, Instant to);

    @Query("""
            select coalesce(sum(t.amount), 0) from Transfer t
            where t.type = com.academy.paybridge.transfer.domain.TransferType.EXTERNAL
              and t.currency = :currency and t.status in :open""")
    BigDecimal sumOpenPayouts(@Param("currency") String currency, @Param("open") Collection<TransferStatus> open);
}

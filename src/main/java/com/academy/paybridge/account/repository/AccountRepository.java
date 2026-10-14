package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByWalletNumber(String walletNumber);

    boolean existsByWalletNumber(String walletNumber);

    List<Account> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    boolean existsByCustomerIdAndCurrency(UUID customerId, String currency);

    Page<Account> findByType(AccountType type, Pageable pageable);

    /** SELECT ... ORDER BY id FOR UPDATE: rows are locked in id order, so two transfers never deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIds(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") UUID id);
}

package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.AccountStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountStatusChangeRepository extends JpaRepository<AccountStatusChange, UUID> {
    List<AccountStatusChange> findByAccountIdOrderByChangedAtDesc(UUID accountId);
}

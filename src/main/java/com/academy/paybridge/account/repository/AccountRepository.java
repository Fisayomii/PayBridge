package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByWalletNumber(String walletNumber);
}
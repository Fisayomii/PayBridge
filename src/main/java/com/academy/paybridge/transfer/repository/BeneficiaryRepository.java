package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.Beneficiary;
import com.academy.paybridge.transfer.domain.TransferType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    List<Beneficiary> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    Optional<Beneficiary> findByIdAndCustomerId(UUID id, UUID customerId);

    Optional<Beneficiary> findByCustomerIdAndTypeAndWalletNumber(UUID customerId, TransferType type, String walletNumber);

    Optional<Beneficiary> findByCustomerIdAndTypeAndAccountNumberAndBankCode(
            UUID customerId, TransferType type, String accountNumber, String bankCode);
}

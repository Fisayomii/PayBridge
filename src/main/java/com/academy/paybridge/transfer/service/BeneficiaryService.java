package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.Beneficiary;
import com.academy.paybridge.transfer.domain.TransferType;
import com.academy.paybridge.transfer.repository.BeneficiaryRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class BeneficiaryService {

    private static final Logger log = LogManager.getLogger(BeneficiaryService.class);
    static final Pattern NUBAN = Pattern.compile("\\d{10}");
    static final Pattern BANK_CODE = Pattern.compile("\\d{3,6}");

    private final BeneficiaryRepository repository;
    private final AccountApi accountApi;
    private final TransferGateway transferGateway;

    public BeneficiaryService(BeneficiaryRepository repository, AccountApi accountApi, TransferGateway transferGateway) {
        this.repository = repository;
        this.accountApi = accountApi;
        this.transferGateway = transferGateway;
    }

    public record BeneficiaryDto(UUID id, String type, String nickname, String walletNumber,
                                 String accountNumber, String bankCode, String name, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<BeneficiaryDto> list(UUID customerId) {
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(BeneficiaryService::toDto).toList();
    }

    public BeneficiaryDto addInternal(UUID customerId, String walletNumber, String nickname) {
        AccountDto wallet = requireRecipientWallet(customerId, walletNumber);
        try {
            return toDto(repository.save(Beneficiary.internal(customerId, wallet.walletNumber(), wallet.ownerName(),
                    trimToNull(nickname))));
        } catch (DataIntegrityViolationException ex) {
            throw PayBridgeException.conflict("This wallet is already a saved beneficiary");
        }
    }

    /** Resolves the account name and registers the Paystack recipient once, up front. */
    public BeneficiaryDto addExternal(UUID customerId, String accountNumber, String bankCode, String nickname) {
        requireNuban(accountNumber, bankCode);
        if (repository.findByCustomerIdAndTypeAndAccountNumberAndBankCode(
                customerId, TransferType.EXTERNAL, accountNumber, bankCode).isPresent()) {
            throw PayBridgeException.conflict("This bank account is already a saved beneficiary");
        }
        TransferGateway.BankResolutionResult resolution = transferGateway.resolveBankAccount(accountNumber, bankCode);
        String recipientCode = transferGateway.createTransferRecipient(resolution.accountName(), accountNumber, bankCode);
        try {
            return toDto(repository.save(Beneficiary.external(customerId, accountNumber, bankCode,
                    resolution.accountName(), recipientCode, trimToNull(nickname))));
        } catch (DataIntegrityViolationException ex) {
            throw PayBridgeException.conflict("This bank account is already a saved beneficiary");
        }
    }

    @Transactional
    public void delete(UUID customerId, UUID beneficiaryId) {
        repository.delete(requireOwned(customerId, beneficiaryId, null));
    }

    /** 404 for someone else's beneficiary as well as a missing one. */
    @Transactional(readOnly = true)
    public Beneficiary requireOwned(UUID customerId, UUID beneficiaryId, TransferType expectedType) {
        Beneficiary b = repository.findByIdAndCustomerId(beneficiaryId, customerId)
                .orElseThrow(() -> PayBridgeException.notFound("Beneficiary not found: " + beneficiaryId));
        if (expectedType != null && b.getType() != expectedType) {
            throw PayBridgeException.badRequest("Beneficiary " + beneficiaryId + " is not an "
                    + expectedType.name().toLowerCase() + " beneficiary");
        }
        return b;
    }

    /** Best-effort save after a transfer; an existing entry is left alone. Returns the beneficiary id. */
    UUID rememberInternal(UUID customerId, String walletNumber, String name) {
        return repository.findByCustomerIdAndTypeAndWalletNumber(customerId, TransferType.INTERNAL, walletNumber)
                .map(Beneficiary::getId)
                .orElseGet(() -> saveQuietly(Beneficiary.internal(customerId, walletNumber, name, null)));
    }

    UUID rememberExternal(UUID customerId, String accountNumber, String bankCode, String name, String recipientCode) {
        return repository.findByCustomerIdAndTypeAndAccountNumberAndBankCode(
                        customerId, TransferType.EXTERNAL, accountNumber, bankCode)
                .map(Beneficiary::getId)
                .orElseGet(() -> saveQuietly(Beneficiary.external(customerId, accountNumber, bankCode, name,
                        recipientCode, null)));
    }

    private UUID saveQuietly(Beneficiary b) {
        try {
            return repository.save(b).getId();
        } catch (DataIntegrityViolationException ex) {
            log.debug("Beneficiary already saved concurrently for customer {}", b.getCustomerId());
            return null;
        }
    }

    private AccountDto requireRecipientWallet(UUID customerId, String walletNumber) {
        if (walletNumber == null || walletNumber.isBlank()) {
            throw PayBridgeException.badRequest("Wallet number is required");
        }
        AccountDto wallet = accountApi.getByWalletNumber(walletNumber.trim());
        if (wallet.type() != AccountType.CUSTOMER || wallet.customerId() == null) {
            throw PayBridgeException.notFound("Wallet not found: " + walletNumber);
        }
        if (wallet.customerId().equals(customerId)) {
            throw PayBridgeException.badRequest("You cannot save your own wallet as a beneficiary");
        }
        return wallet;
    }

    static void requireNuban(String accountNumber, String bankCode) {
        if (accountNumber == null || !NUBAN.matcher(accountNumber).matches()) {
            throw PayBridgeException.badRequest("Account number must be a 10-digit NUBAN");
        }
        if (bankCode == null || !BANK_CODE.matcher(bankCode).matches()) {
            throw PayBridgeException.badRequest("Bank code must be 3-6 digits");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BeneficiaryDto toDto(Beneficiary b) {
        return new BeneficiaryDto(b.getId(), b.getType().name(), b.getNickname(), b.getWalletNumber(),
                TransferEventPublisher.maskAccountNumber(b.getAccountNumber()), b.getBankCode(),
                b.getResolvedName(), b.getCreatedAt());
    }
}

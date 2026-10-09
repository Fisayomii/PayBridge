package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService implements AccountApi {

    private final AccountRepository accountRepository;

    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    public AccountService(AccountRepository accountRepository, org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void seedDemoWallets() {
        if (accountRepository.count() == 0) {
            accountRepository.save(new Account(
                    "WALLET-1001",
                    "Adebayo Ogunlesi (Sender Wallet)",
                    "sender@paybridge.local",
                    "0000000000",
                    "058",
                    new BigDecimal("100000.00"),
                    "NGN"
            ));
            accountRepository.save(new Account(
                    "WALLET-2002",
                    "Chidinma Okonkwo (Receiver Wallet)",
                    "receiver@paybridge.local",
                    "0000000000",
                    "058",
                    new BigDecimal("20000.00"),
                    "NGN"
            ));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDto getByWalletNumber(String walletNumber) {
        Account acc = accountRepository.findByWalletNumber(walletNumber)
                .orElseThrow(() -> PayBridgeException.notFound("Wallet not found: " + walletNumber));
        return toDto(acc);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDto getById(UUID accountId) {
        Account acc = accountRepository.findById(accountId)
                .orElseThrow(() -> PayBridgeException.notFound("Account ID not found: " + accountId));
        return toDto(acc);
    }

    @Override
    @Transactional
    public void debit(UUID accountId, BigDecimal amount, String currency) {
        Account acc = accountRepository.findById(accountId)
                .orElseThrow(() -> PayBridgeException.notFound("Account not found: " + accountId));
        if (!acc.getCurrency().equalsIgnoreCase(currency)) {
            throw PayBridgeException.badRequest("Currency mismatch on debit: expected " + acc.getCurrency());
        }
        if (acc.getBalance().compareTo(amount) < 0) {
            throw PayBridgeException.badRequest("Insufficient balance in wallet " + acc.getWalletNumber());
        }
        acc.debit(amount);
        accountRepository.save(acc);
    }

    @Override
    @Transactional
    public void credit(UUID accountId, BigDecimal amount, String currency) {
        Account acc = accountRepository.findById(accountId)
                .orElseThrow(() -> PayBridgeException.notFound("Account not found: " + accountId));
        if (!acc.getCurrency().equalsIgnoreCase(currency)) {
            throw PayBridgeException.badRequest("Currency mismatch on credit: expected " + acc.getCurrency());
        }
        acc.credit(amount);
        accountRepository.save(acc);
    }

    @Transactional(readOnly = true)
    public List<AccountDto> listAllWallets() {
        return accountRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public AccountDto createWallet(String walletNumber, String ownerName, String email,
                                   String bankAcc, String bankCode, BigDecimal initialBalance) {
        Account saved = accountRepository.save(new Account(
                walletNumber, ownerName, email, bankAcc, bankCode, initialBalance, "NGN"
        ));
        eventPublisher.publishEvent(new com.academy.paybridge.notification.api.AccountCreatedEvent(
                saved.getWalletNumber(), saved.getOwnerName(), saved.getEmail()
        ));
        return toDto(saved);
    }

    private AccountDto toDto(Account a) {
        return new AccountDto(
                a.getId(),
                a.getWalletNumber(),
                a.getOwnerName(),
                a.getEmail(),
                a.getExternalBankAccountNumber(),
                a.getExternalBankCode(),
                a.getBalance(),
                a.getCurrency()
        );
    }
}
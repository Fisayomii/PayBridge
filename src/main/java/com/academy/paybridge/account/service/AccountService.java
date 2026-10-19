package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.MovementKind;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.AccountStatusChange;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.repository.AccountStatusChangeRepository;
import com.academy.paybridge.notification.api.AccountCreatedEvent;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccountService implements AccountApi {

    private static final Logger log = LogManager.getLogger(AccountService.class);

    /** USD wallets arrive with the remittance module. */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("NGN");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final AccountStatusChangeRepository statusChangeRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AccountService(AccountRepository accountRepository,
                          AccountStatusChangeRepository statusChangeRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.statusChangeRepository = statusChangeRepository;
        this.eventPublisher = eventPublisher;
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
    @Transactional(readOnly = true)
    public List<AccountDto> getByCustomer(UUID customerId) {
        return accountRepository.findByCustomerIdOrderByCreatedAtAsc(customerId).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public AccountDto openWallet(UUID customerId, String ownerName, String email, String currency) {
        String ccy = currency == null ? "" : currency.trim().toUpperCase();
        if (!SUPPORTED_CURRENCIES.contains(ccy)) {
            throw PayBridgeException.badRequest("Unsupported wallet currency: " + currency);
        }
        if (accountRepository.existsByCustomerIdAndCurrency(customerId, ccy)) {
            throw PayBridgeException.conflict("You already have a " + ccy + " wallet");
        }
        Account saved = accountRepository.save(
                Account.customerWallet(newWalletNumber(), customerId, ownerName, email, ccy));
        eventPublisher.publishEvent(new AccountCreatedEvent(saved.getWalletNumber(), saved.getOwnerName(), saved.getEmail()));
        return toDto(saved);
    }

    @Override
    @Transactional
    public AccountDto openPartnerAccount(UUID partnerId, String partnerName, String email, String currency) {
        String ccy = currency.trim().toUpperCase();
        if (accountRepository.existsByCustomerIdAndCurrency(partnerId, ccy)) {
            throw PayBridgeException.conflict("Partner already has a " + ccy + " prefund account");
        }
        String number = "PARTNER-" + ccy + "-" + String.format("%06d", RANDOM.nextInt(1_000_000));
        return toDto(accountRepository.save(Account.partnerPrefund(number, partnerId, partnerName, email, ccy)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountDto> listAll() {
        return accountRepository.findAll(Sort.by("currency", "type", "walletNumber")).stream()
                .map(this::toDto).toList();
    }

    @Override
    @Transactional
    public void moveFunds(UUID debitAccountId, UUID creditAccountId, BigDecimal amount, String currency,
                          MovementKind kind) {
        if (amount == null || amount.signum() <= 0) {
            throw PayBridgeException.badRequest("Amount must be positive");
        }
        if (debitAccountId.equals(creditAccountId)) {
            throw PayBridgeException.badRequest("Cannot move funds to the same account");
        }
        Map<UUID, Account> locked = accountRepository.lockAllByIds(List.of(debitAccountId, creditAccountId))
                .stream().collect(Collectors.toMap(Account::getId, Function.identity()));

        Account debitAcc = locked.get(debitAccountId);
        Account creditAcc = locked.get(creditAccountId);
        if (debitAcc == null || creditAcc == null) {
            throw PayBridgeException.notFound("Account not found: "
                    + (debitAcc == null ? debitAccountId : creditAccountId));
        }
        for (Account acc : List.of(debitAcc, creditAcc)) {
            if (!acc.getCurrency().equalsIgnoreCase(currency)) {
                throw PayBridgeException.badRequest("Currency mismatch: wallet " + acc.getWalletNumber()
                        + " is " + acc.getCurrency() + ", transfer is " + currency);
            }
        }
        if (kind == MovementKind.CUSTOMER_INITIATED) {
            if (debitAcc.getType() == AccountType.CUSTOMER && !debitAcc.getStatus().allowsCustomerDebit()) {
                throw PayBridgeException.unprocessable("Wallet " + debitAcc.getWalletNumber()
                        + " is " + debitAcc.getStatus() + " and cannot send funds");
            }
            if (creditAcc.getType() == AccountType.CUSTOMER && !creditAcc.getStatus().allowsCustomerCredit()) {
                throw PayBridgeException.unprocessable("Wallet " + creditAcc.getWalletNumber()
                        + " cannot receive funds at this time");
            }
        }
        if (debitAcc.getType() != AccountType.INTERNAL && debitAcc.getBalance().compareTo(amount) < 0) {
            throw PayBridgeException.unprocessable("Insufficient funds in wallet " + debitAcc.getWalletNumber());
        }
        debitAcc.debit(amount);
        creditAcc.credit(amount);
    }

    @Override
    @Transactional
    public Map<UUID, AccountDto> lockForUpdate(Collection<UUID> accountIds) {
        Set<UUID> ids = new HashSet<>(accountIds);
        Map<UUID, AccountDto> locked = accountRepository.lockAllByIds(ids).stream()
                .collect(Collectors.toMap(Account::getId, this::toDto));
        if (locked.size() != ids.size()) {
            throw PayBridgeException.notFound("Account not found");
        }
        return locked;
    }

    /** Wallet visible to the caller: their own, or any customer wallet for an admin. */
    @Transactional(readOnly = true)
    public AccountDto getForUser(AuthenticatedUser user, String walletNumber) {
        Account acc = accountRepository.findByWalletNumber(walletNumber)
                .filter(a -> a.getType() == AccountType.CUSTOMER)
                .filter(a -> user.isAdmin() || user.customerId().equals(a.getCustomerId()))
                // Same response whether it does not exist or belongs to someone else: no wallet enumeration.
                .orElseThrow(() -> PayBridgeException.notFound("Wallet not found: " + walletNumber));
        return toDto(acc);
    }

    /** Internal name enquiry: who will receive a wallet-to-wallet transfer. */
    @Transactional(readOnly = true)
    public WalletLookup lookup(String walletNumber) {
        Account acc = accountRepository.findByWalletNumber(walletNumber)
                .filter(a -> a.getType() == AccountType.CUSTOMER && a.getCustomerId() != null)
                .orElseThrow(() -> PayBridgeException.notFound("Wallet not found: " + walletNumber));
        return new WalletLookup(acc.getWalletNumber(), acc.getOwnerName(), acc.getCurrency());
    }

    public record WalletLookup(String walletNumber, String accountName, String currency) {}

    @Transactional(readOnly = true)
    public Page<AccountDto> listCustomerWallets(int page, int size) {
        return accountRepository.findByType(AccountType.CUSTOMER,
                        PageRequest.of(page, Math.min(size, 200), Sort.by("createdAt").descending()))
                .map(this::toDto);
    }

    @Transactional
    public AccountDto changeStatus(String walletNumber, AccountStatus newStatus, String reason, UUID changedBy) {
        if (reason == null || reason.isBlank()) {
            throw PayBridgeException.badRequest("A reason is required to change wallet status");
        }
        Account acc = accountRepository.findByWalletNumber(walletNumber)
                .filter(a -> a.getType() == AccountType.CUSTOMER)
                .orElseThrow(() -> PayBridgeException.notFound("Wallet not found: " + walletNumber));
        acc = accountRepository.lockById(acc.getId()).orElseThrow();
        AccountStatus old = acc.getStatus();
        if (old == newStatus) {
            return toDto(acc);
        }
        acc.changeStatus(newStatus);
        statusChangeRepository.save(new AccountStatusChange(acc.getId(), old, newStatus, reason.trim(), changedBy));
        log.info("Wallet {} status {} -> {} by {}: {}", walletNumber, old, newStatus, changedBy, reason);
        return toDto(acc);
    }

    @Transactional(readOnly = true)
    public List<AccountStatusChange> statusHistory(String walletNumber) {
        Account acc = accountRepository.findByWalletNumber(walletNumber)
                .orElseThrow(() -> PayBridgeException.notFound("Wallet not found: " + walletNumber));
        return statusChangeRepository.findByAccountIdOrderByChangedAtDesc(acc.getId());
    }

    /** 10-digit, NUBAN-shaped wallet numbers starting with 9, unique-checked. */
    private String newWalletNumber() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "9" + String.format("%09d", RANDOM.nextInt(1_000_000_000));
            if (!accountRepository.existsByWalletNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique wallet number");
    }

    private AccountDto toDto(Account a) {
        return new AccountDto(
                a.getId(),
                a.getWalletNumber(),
                a.getCustomerId(),
                a.getOwnerName(),
                a.getEmail(),
                a.getBalance(),
                a.getCurrency(),
                a.getType(),
                a.getStatus(),
                a.getCreatedAt()
        );
    }
}

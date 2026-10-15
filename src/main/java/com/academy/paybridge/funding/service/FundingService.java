package com.academy.paybridge.funding.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.account.api.MovementKind;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.funding.client.CollectionGateway;
import com.academy.paybridge.funding.client.CollectionGateway.ChargeVerification;
import com.academy.paybridge.funding.domain.Funding;
import com.academy.paybridge.funding.domain.FundingDirection;
import com.academy.paybridge.funding.domain.FundingStatus;
import com.academy.paybridge.funding.repository.FundingRepository;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.notification.api.FundingEvent;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The only ways money enters a wallet from outside PayBridge. Every credit is a balanced ledger posting
 * against a GL account, so the sum of customer balances always equals what was collected minus what was paid out.
 */
@Service
public class FundingService {

    private static final Logger log = LogManager.getLogger(FundingService.class);
    private static final BigDecimal MIN_CHECKOUT_AMOUNT = new BigDecimal("100.00");

    private final FundingRepository fundingRepository;
    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final LedgerApi ledgerApi;
    private final ComplianceApi complianceApi;
    private final CollectionGateway collectionGateway;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate tx;
    private final String callbackUrl;

    public FundingService(FundingRepository fundingRepository,
                          AccountApi accountApi,
                          CustomerApi customerApi,
                          LedgerApi ledgerApi,
                          ComplianceApi complianceApi,
                          CollectionGateway collectionGateway,
                          ApplicationEventPublisher eventPublisher,
                          TransactionTemplate tx,
                          @Value("${paybridge.funding.callback-url:}") String callbackUrl) {
        this.fundingRepository = fundingRepository;
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.ledgerApi = ledgerApi;
        this.complianceApi = complianceApi;
        this.collectionGateway = collectionGateway;
        this.eventPublisher = eventPublisher;
        this.tx = tx;
        this.callbackUrl = callbackUrl;
    }

    public record FundingDto(String reference, String walletNumber, String channel, String direction,
                             BigDecimal amount, String currency, String status, String authorizationUrl,
                             String reason, Instant createdAt, Instant completedAt) {}

    // ------------------------------------------------------------------ customer checkout

    /** Creates a Paystack checkout for topping up the caller's wallet. The wallet is credited only after verification. */
    public FundingDto startCheckout(AuthenticatedUser user, String walletNumber, BigDecimal rawAmount, String idempotencyKey) {
        BigDecimal amount = normalizeAmount(rawAmount);
        if (amount.compareTo(MIN_CHECKOUT_AMOUNT) < 0) {
            throw PayBridgeException.badRequest("Minimum top-up is NGN " + MIN_CHECKOUT_AMOUNT.toPlainString());
        }
        String key = requireKey(idempotencyKey);
        CustomerDto customer = customerApi.requireActive(user.customerId());
        AccountDto wallet = requireOwnedWallet(customer.id(), walletNumber);

        var existing = fundingRepository.findByInitiatedByAndIdempotencyKey(customer.id(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), wallet.id(), amount);
        }
        if (!wallet.status().allowsCustomerCredit()) {
            throw PayBridgeException.unprocessable("Wallet " + wallet.walletNumber() + " cannot receive funds at this time");
        }
        complianceApi.checkInboundCredit(customer.kycTier(), wallet.balance(), amount);

        String reference = "pb-fnd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Funding funding;
        try {
            funding = fundingRepository.saveAndFlush(
                    Funding.checkout(reference, wallet.id(), amount, wallet.currency(), customer.id(), key));
        } catch (DataIntegrityViolationException ex) {
            return replay(fundingRepository.findByInitiatedByAndIdempotencyKey(customer.id(), key).orElseThrow(() -> ex),
                    wallet.id(), amount);
        }

        try {
            CollectionGateway.CheckoutSession session = collectionGateway.initializeCheckout(
                    customer.email(), amount, wallet.currency(), reference, callbackUrl);
            funding.attachCheckout(session.authorizationUrl());
            return toDto(fundingRepository.save(funding), wallet.walletNumber());
        } catch (PayBridgeException ex) {
            // No checkout URL was handed out, so the customer cannot pay against this reference.
            tx.executeWithoutResult(s -> fundingRepository.lockByReference(reference)
                    .ifPresent(f -> f.markFailed("Checkout could not be created: " + ex.getMessage())));
            throw ex;
        }
    }

    /**
     * Verifies a checkout with the provider and credits the wallet exactly once. Called by the charge webhook,
     * by the customer after returning from checkout, and by the reconciler. The webhook body is never trusted
     * for the amount: the server-side verification is.
     */
    public FundingDto confirmCheckout(String reference) {
        Funding funding = fundingRepository.findByReference(reference)
                .orElseThrow(() -> PayBridgeException.notFound("Funding not found: " + reference));
        if (funding.getStatus() != FundingStatus.PENDING) {
            return toDto(funding, walletNumber(funding.getAccountId()));
        }
        ChargeVerification verification = collectionGateway.verifyCharge(reference);
        return apply(reference, verification);
    }

    public FundingDto verifyForUser(AuthenticatedUser user, String reference) {
        requireVisible(user, reference);
        return confirmCheckout(reference);
    }

    @Transactional(readOnly = true)
    public FundingDto getForUser(AuthenticatedUser user, String reference) {
        Funding f = requireVisible(user, reference);
        return toDto(f, walletNumber(f.getAccountId()));
    }

    @Transactional(readOnly = true)
    public List<FundingDto> listForWallet(AuthenticatedUser user, String walletNumber, int page, int size) {
        AccountDto wallet = user.isAdmin() ? accountApi.getByWalletNumber(walletNumber)
                : requireOwnedWallet(user.customerId(), walletNumber);
        return fundingRepository.findByAccountIdOrderByCreatedAtDesc(wallet.id(), PageRequest.of(page, Math.min(size, 100)))
                .stream().map(f -> toDto(f, wallet.walletNumber())).toList();
    }

    private FundingDto apply(String reference, ChargeVerification v) {
        FundingDto result = tx.execute(status -> {
            Funding f = fundingRepository.lockByReference(reference).orElseThrow();
            if (f.getStatus() != FundingStatus.PENDING) {
                return toDto(f, walletNumber(f.getAccountId()));
            }
            switch (v.status()) {
                case SUCCESS -> {
                    long expectedMinor = Money.of(f.getAmount(), f.getCurrency()).toMinorUnits();
                    if (v.amountMinor() != expectedMinor || !f.getCurrency().equalsIgnoreCase(v.currency())) {
                        log.error("RECONCILIATION ALERT: funding {} paid {} {} (minor) but {} {} was expected. "
                                        + "Not credited; manual review required.",
                                reference, v.amountMinor(), v.currency(), expectedMinor, f.getCurrency());
                        f.markFailed("Amount or currency mismatch with provider; under review");
                        return toDto(f, walletNumber(f.getAccountId()));
                    }
                    // SYSTEM: the money has already been received, so a freeze must not make it vanish.
                    post(reference, glAccount(InternalAccounts.NGN_PAYSTACK_COLLECTIONS), f.getAccountId(),
                            f.getAmount(), f.getCurrency(), "Wallet top-up via Paystack");
                    f.markSucceeded(v.providerReference());
                    publish(f);
                }
                case FAILED -> f.markFailed(v.message());
                case ABANDONED -> f.markAbandoned(v.message());
                case PENDING, NOT_FOUND -> { /* still waiting */ }
            }
            return toDto(f, walletNumber(f.getAccountId()));
        });
        return result;
    }

    /** Verifies stale checkouts; abandons ones the customer never paid. */
    public int reconcileStaleCheckouts(Duration minAge, Duration abandonAfter, int batchSize) {
        List<Funding> stale = fundingRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                FundingStatus.PENDING, Instant.now().minus(minAge), PageRequest.of(0, batchSize));
        for (Funding f : stale) {
            try {
                FundingDto dto = confirmCheckout(f.getReference());
                if ("PENDING".equals(dto.status()) && f.getCreatedAt().isBefore(Instant.now().minus(abandonAfter))) {
                    tx.executeWithoutResult(s -> fundingRepository.lockByReference(f.getReference())
                            .filter(locked -> locked.getStatus() == FundingStatus.PENDING)
                            .ifPresent(locked -> locked.markAbandoned("Checkout not completed")));
                }
            } catch (RuntimeException ex) {
                log.warn("Could not reconcile funding {}: {}", f.getReference(), ex.getMessage());
            }
        }
        return stale.size();
    }

    // ------------------------------------------------------------------ admin adjustments

    /** Audited manual credit/debit (corrections, sandbox top-ups). Posted against GL-NGN-ADJUSTMENTS. */
    public FundingDto adjust(AuthenticatedUser admin, String walletNumber, FundingDirection direction,
                             BigDecimal rawAmount, String reason, String idempotencyKey) {
        BigDecimal amount = normalizeAmount(rawAmount);
        String key = requireKey(idempotencyKey);
        if (reason == null || reason.isBlank()) {
            throw PayBridgeException.badRequest("A reason is required for adjustments");
        }
        AccountDto wallet = accountApi.getByWalletNumber(walletNumber);
        if (wallet.type() != AccountType.CUSTOMER) {
            throw PayBridgeException.notFound("Wallet not found: " + walletNumber);
        }
        var existing = fundingRepository.findByInitiatedByAndIdempotencyKey(admin.customerId(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), wallet.id(), amount);
        }
        UUID adjustments = accountApi.getByWalletNumber(InternalAccounts.NGN_ADJUSTMENTS).id();
        String reference = "pb-adj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try {
            FundingDto dto = tx.execute(s -> {
                Funding f = fundingRepository.saveAndFlush(Funding.adjustment(reference, wallet.id(), direction, amount,
                        wallet.currency(), reason.trim(), admin.customerId(), key));
                if (direction == FundingDirection.CREDIT) {
                    post(reference, adjustments, wallet.id(), amount, wallet.currency(), "Adjustment: " + reason.trim());
                } else {
                    post(reference, wallet.id(), adjustments, amount, wallet.currency(), "Adjustment: " + reason.trim());
                }
                publish(f);
                return toDto(f, wallet.walletNumber());
            });
            log.info("AUDIT adjustment {} {} NGN {} on {} by {}: {}", reference, direction, amount,
                    walletNumber, admin.customerId(), reason);
            return dto;
        } catch (DataIntegrityViolationException ex) {
            return replay(fundingRepository.findByInitiatedByAndIdempotencyKey(admin.customerId(), key).orElseThrow(() -> ex),
                    wallet.id(), amount);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void post(String reference, UUID debit, UUID credit, BigDecimal amount, String currency, String narration) {
        accountApi.moveFunds(debit, credit, amount, currency, MovementKind.SYSTEM);
        ledgerApi.recordDoubleEntry(reference, debit, credit, amount, currency, narration);
    }

    private UUID glAccount(String glWalletNumber) {
        return accountApi.getByWalletNumber(glWalletNumber).id();
    }

    private void publish(Funding f) {
        AccountDto wallet = accountApi.getById(f.getAccountId());
        eventPublisher.publishEvent(new FundingEvent(f.getReference(), wallet.walletNumber(), wallet.ownerName(),
                wallet.email(), f.getChannel().name(), f.getDirection().name(), f.getAmount(), f.getCurrency(),
                wallet.balance()));
    }

    private Funding requireVisible(AuthenticatedUser user, String reference) {
        Funding f = fundingRepository.findByReference(reference)
                .orElseThrow(() -> PayBridgeException.notFound("Funding not found: " + reference));
        if (!user.isAdmin() && !user.customerId().equals(accountApi.getById(f.getAccountId()).customerId())) {
            throw PayBridgeException.notFound("Funding not found: " + reference);
        }
        return f;
    }

    private AccountDto requireOwnedWallet(UUID customerId, String walletNumber) {
        if (walletNumber == null || walletNumber.isBlank()) {
            throw PayBridgeException.badRequest("walletNumber is required");
        }
        AccountDto wallet;
        try {
            wallet = accountApi.getByWalletNumber(walletNumber.trim());
        } catch (PayBridgeException ex) {
            throw PayBridgeException.notFound("Wallet not found: " + walletNumber);
        }
        if (wallet.type() != AccountType.CUSTOMER || !customerId.equals(wallet.customerId())) {
            throw PayBridgeException.notFound("Wallet not found: " + walletNumber);
        }
        return wallet;
    }

    private FundingDto replay(Funding existing, UUID accountId, BigDecimal amount) {
        if (!existing.getAccountId().equals(accountId) || existing.getAmount().compareTo(amount) != 0) {
            throw PayBridgeException.conflict("Idempotency-Key was already used for a different request");
        }
        return toDto(existing, walletNumber(existing.getAccountId()));
    }

    private String walletNumber(UUID accountId) {
        return accountApi.getById(accountId).walletNumber();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw PayBridgeException.badRequest("Idempotency-Key header is required (max 255 characters)");
        }
        return key.trim();
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw PayBridgeException.badRequest("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw PayBridgeException.badRequest("Amount cannot have more than 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static FundingDto toDto(Funding f, String walletNumber) {
        return new FundingDto(f.getReference(), walletNumber, f.getChannel().name(), f.getDirection().name(),
                f.getAmount(), f.getCurrency(), f.getStatus().name(), f.getAuthorizationUrl(), f.getReason(),
                f.getCreatedAt(), f.getCompletedAt());
    }
}

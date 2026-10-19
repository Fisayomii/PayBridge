package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.account.api.MovementKind;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.ComplianceApi.Decision;
import com.academy.paybridge.compliance.api.ComplianceApi.RemittanceScreening;
import com.academy.paybridge.compliance.api.ComplianceApi.ScreeningResult;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteDto;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryDto;
import com.academy.paybridge.notification.api.RemittanceEvent;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceDto;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.Role;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound USD -> NGN remittances from prefunded partners, paid into PayBridge wallets.
 *
 * <p>Postings (reference suffix in brackets). USD and NGN each balance on their own:
 * <pre>
 *   accepted   PARTNER-USD                 -> GL-USD-REMITTANCE-CLEARING    [ref]       amount + fee
 *   paid out   GL-USD-REMITTANCE-CLEARING  -> GL-USD-FX-POSITION            [ref-USD]   amount
 *              GL-USD-REMITTANCE-CLEARING  -> GL-USD-REMITTANCE-FEE-INCOME  [ref-FEE]   fee
 *              GL-NGN-FX-POSITION          -> recipient wallet              [ref-NGN]   amount x locked rate
 *   refunded   GL-USD-REMITTANCE-CLEARING  -> PARTNER-USD                   [ref-REF]   amount + fee
 * </pre>
 * A clear remittance goes through accepted and paid out in one transaction. A held one stops after accepted
 * until an analyst approves (paid out) or rejects (refunded).
 */
@Service
@EnableConfigurationProperties(RemittanceProperties.class)
public class RemittanceService implements RemittanceApi {

    private static final Logger log = LogManager.getLogger(RemittanceService.class);

    private final RemittanceRepository remittanceRepository;
    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final LedgerApi ledgerApi;
    private final ComplianceApi complianceApi;
    private final FxApi fxApi;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate tx;
    private final RemittanceProperties props;

    public RemittanceService(RemittanceRepository remittanceRepository, AccountApi accountApi,
                             CustomerApi customerApi, LedgerApi ledgerApi, ComplianceApi complianceApi,
                             FxApi fxApi, ApplicationEventPublisher eventPublisher, TransactionTemplate tx,
                             RemittanceProperties props) {
        this.remittanceRepository = remittanceRepository;
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.ledgerApi = ledgerApi;
        this.complianceApi = complianceApi;
        this.fxApi = fxApi;
        this.eventPublisher = eventPublisher;
        this.tx = tx;
        this.props = props;
    }

    public record CreateRemittanceCommand(
            String quoteReference,
            String partnerReference,
            String senderName,
            String senderCountry,
            String senderIdNumber,
            String senderAddress,
            String recipientName,
            String recipientWalletNumber,
            String purpose
    ) {}

    // ------------------------------------------------------------------ partner side

    public QuoteDto quote(AuthenticatedUser partner, BigDecimal amountUsd) {
        requirePartner(partner);
        if (amountUsd == null || amountUsd.scale() > 2) {
            throw PayBridgeException.badRequest("Amount must have at most 2 decimal places");
        }
        if (amountUsd.compareTo(props.minAmountUsd()) < 0 || amountUsd.compareTo(props.maxAmountUsd()) > 0) {
            throw PayBridgeException.unprocessable("Remittance amount must be between USD "
                    + props.minAmountUsd().toPlainString() + " and USD " + props.maxAmountUsd().toPlainString());
        }
        return fxApi.createQuote(partner.customerId(), amountUsd.setScale(2, RoundingMode.UNNECESSARY));
    }

    /**
     * Accepts a remittance against a quote. Idempotent per (partner, Idempotency-Key): a retry returns the
     * original result and never charges twice; the same key with a different body is a 409.
     */
    public RemittanceDto create(AuthenticatedUser partner, CreateRemittanceCommand cmd, String idempotencyKey) {
        requirePartner(partner);
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint(cmd);

        var existing = remittanceRepository.findByPartnerIdAndIdempotencyKey(partner.customerId(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        AccountDto partnerAccount = partnerAccount(partner.customerId());
        AccountDto recipient = requireRecipientWallet(cmd.recipientWalletNumber());

        try {
            return tx.execute(s -> {
                QuoteDto quote = fxApi.consumeQuote(cmd.quoteReference(), partner.customerId());
                ScreeningResult screening = complianceApi.screenRemittance(new RemittanceScreening(
                        cmd.senderName(), cmd.senderCountry(), cmd.recipientName(), recipient.ownerName(),
                        quote.sourceAmount(), cmd.purpose()));

                Remittance r = new Remittance(newReference(), partner.customerId(), partnerAccount.id(), quote.id(),
                        key, fingerprint, blankToNull(cmd.partnerReference()), cmd.senderName().trim(),
                        cmd.senderCountry().trim().toUpperCase(Locale.ROOT), cmd.senderIdNumber().trim(),
                        cmd.senderAddress().trim(), cmd.recipientName().trim(), recipient.walletNumber(),
                        recipient.id(), cmd.purpose().trim(), quote.sourceAmount(), quote.fee(), quote.rate(),
                        quote.targetAmount());

                if (screening.decision() == Decision.BLOCK) {
                    r.reject(screening.reasons(), null, "Blocked by automated screening");
                    remittanceRepository.saveAndFlush(r);
                    log.warn("AUDIT remittance {} from partner {} blocked: {}", r.getReference(),
                            partner.customerId(), screening.reasons());
                    return toDto(r);
                }

                Accounts gl = accounts();
                Map<UUID, AccountDto> locked = accountApi.lockForUpdate(List.of(partnerAccount.id(), gl.usdClearing,
                        gl.usdFxPosition, gl.usdFeeIncome, gl.ngnFxPosition, recipient.id()));
                if (locked.get(partnerAccount.id()).balance().compareTo(r.totalCharged()) < 0) {
                    throw PayBridgeException.unprocessable("INSUFFICIENT_PREFUND: This remittance needs USD "
                            + r.totalCharged().toPlainString() + " but your prefund balance is USD "
                            + locked.get(partnerAccount.id()).balance().toPlainString() + ". Top up your prefund.");
                }
                if (screening.isClear()) {
                    // Fail fast to the partner, before charging, if the wallet cannot take the money.
                    checkRecipientCanReceive(locked.get(recipient.id()), r.getTargetAmount());
                }
                post(r.getReference(), partnerAccount.id(), gl.usdClearing, r.totalCharged(), "USD",
                        "Remittance accepted: " + r.getSenderName() + " -> " + r.getRecipientName());
                if (screening.isClear()) {
                    payOut(r, gl, null, "Cleared by automated screening");
                } else {
                    r.holdForReview(screening.reasons());
                    log.info("Remittance {} held for review: {}", r.getReference(), screening.reasons());
                }
                remittanceRepository.saveAndFlush(r);
                return toDto(r);
            });
        } catch (DataIntegrityViolationException ex) {
            // Two requests with the same key raced; the loser returns the winner's result.
            return remittanceRepository.findByPartnerIdAndIdempotencyKey(partner.customerId(), key)
                    .map(r -> replay(r, fingerprint)).orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public RemittanceDto getForPartner(AuthenticatedUser partner, String reference) {
        return remittanceRepository.findByReference(reference)
                .filter(r -> r.getPartnerId().equals(partner.customerId()))
                .map(this::toDto)
                .orElseThrow(() -> PayBridgeException.notFound("Remittance not found: " + reference));
    }

    @Transactional(readOnly = true)
    public Page<RemittanceDto> listForPartner(AuthenticatedUser partner, int page, int size) {
        return remittanceRepository.findByPartnerIdOrderByCreatedAtDesc(partner.customerId(),
                PageRequest.of(page, Math.min(size, 100))).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public PartnerAccountView partnerAccountView(AuthenticatedUser partner) {
        requirePartner(partner);
        AccountDto account = partnerAccount(partner.customerId());
        return new PartnerAccountView(account.walletNumber(), account.ownerName(), account.balance(), account.currency());
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryDto> partnerStatement(AuthenticatedUser partner, int page, int size) {
        requirePartner(partner);
        return ledgerApi.getEntriesForAccount(partnerAccount(partner.customerId()).id(), page, Math.min(size, 200));
    }

    public record PartnerAccountView(String accountNumber, String partnerName, BigDecimal prefundBalance,
                                     String currency) {}

    // ------------------------------------------------------------------ analyst side

    @Transactional(readOnly = true)
    public Page<RemittanceDto> listForAdmin(RemittanceStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200));
        return (status == null
                ? remittanceRepository.findAllByOrderByCreatedAtDesc(pageable)
                : remittanceRepository.findByStatusOrderByCreatedAtAsc(status, pageable)).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public RemittanceDto getForAdmin(String reference) {
        return toDto(remittanceRepository.findByReference(reference)
                .orElseThrow(() -> PayBridgeException.notFound("Remittance not found: " + reference)));
    }

    /**
     * Pays out a held remittance at the rate locked when it was accepted (PayBridge carries the FX risk while
     * it waits). If the wallet can no longer take the money, the partner is refunded and the remittance FAILS.
     */
    @Transactional
    public RemittanceDto approve(String reference, UUID adminId, String note) {
        Remittance r = lockForReview(reference);
        Accounts gl = accounts();
        Map<UUID, AccountDto> locked = accountApi.lockForUpdate(List.of(r.getPartnerAccountId(), gl.usdClearing,
                gl.usdFxPosition, gl.usdFeeIncome, gl.ngnFxPosition, r.getRecipientAccountId()));
        try {
            checkRecipientCanReceive(locked.get(r.getRecipientAccountId()), r.getTargetAmount());
        } catch (PayBridgeException ex) {
            refund(r, gl);
            r.fail(ex.getMessage(), adminId, note);
            log.info("AUDIT remittance {} approved by {} but payout failed: {}", reference, adminId, ex.getMessage());
            return toDto(r);
        }
        payOut(r, gl, adminId, note);
        log.info("AUDIT remittance {} approved by {}: {}", reference, adminId, note);
        return toDto(r);
    }

    @Transactional
    public RemittanceDto reject(String reference, UUID adminId, String note) {
        Remittance r = lockForReview(reference);
        Accounts gl = accounts();
        accountApi.lockForUpdate(List.of(r.getPartnerAccountId(), gl.usdClearing));
        refund(r, gl);
        r.reject(null, adminId, note);
        log.info("AUDIT remittance {} rejected by {}: {}", reference, adminId, note);
        return toDto(r);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal heldForReviewTotal() {
        return remittanceRepository.sumChargedByStatus(RemittanceStatus.PENDING_REVIEW);
    }

    // ------------------------------------------------------------------ internals

    private void payOut(Remittance r, Accounts gl, UUID reviewer, String note) {
        String narration = "Remittance from " + r.getSenderName() + " (" + r.getSenderCountry() + ")";
        post(r.getReference() + "-USD", gl.usdClearing, gl.usdFxPosition, r.getSourceAmount(), "USD", narration);
        post(r.getReference() + "-FEE", gl.usdClearing, gl.usdFeeIncome, r.getFee(), "USD", "Remittance fee");
        post(r.getReference() + "-NGN", gl.ngnFxPosition, r.getRecipientAccountId(), r.getTargetAmount(), "NGN",
                narration);
        r.complete(reviewer, note);
        AccountDto wallet = accountApi.getById(r.getRecipientAccountId());
        eventPublisher.publishEvent(new RemittanceEvent(r.getReference(), wallet.walletNumber(), wallet.ownerName(),
                wallet.email(), r.getSenderName(), r.getSenderCountry(), r.getTargetAmount(), "NGN", wallet.balance()));
    }

    private void refund(Remittance r, Accounts gl) {
        post(r.getReference() + "-REF", gl.usdClearing, r.getPartnerAccountId(), r.totalCharged(), "USD",
                "Remittance refund");
    }

    private void post(String reference, UUID from, UUID to, BigDecimal amount, String currency, String narration) {
        if (amount.signum() == 0) {
            return;
        }
        accountApi.moveFunds(from, to, amount, currency, MovementKind.SYSTEM);
        ledgerApi.recordDoubleEntry(reference, from, to, amount, currency, narration);
    }

    private Remittance lockForReview(String reference) {
        Remittance r = remittanceRepository.lockByReference(reference)
                .orElseThrow(() -> PayBridgeException.notFound("Remittance not found: " + reference));
        if (r.getStatus() != RemittanceStatus.PENDING_REVIEW) {
            throw PayBridgeException.conflict("Remittance " + reference + " is already " + r.getStatus());
        }
        return r;
    }

    /** Frozen wallets cannot receive; the wallet's KYC tier caps its balance. */
    private void checkRecipientCanReceive(AccountDto wallet, BigDecimal amount) {
        if (!wallet.status().allowsCustomerCredit()) {
            throw PayBridgeException.unprocessable("RECIPIENT_UNAVAILABLE: Wallet " + wallet.walletNumber()
                    + " cannot receive funds at this time");
        }
        CustomerDto owner = customerApi.getById(wallet.customerId());
        complianceApi.checkInboundCredit(owner.kycTier(), wallet.balance(), amount);
    }

    private AccountDto requireRecipientWallet(String walletNumber) {
        AccountDto wallet;
        try {
            wallet = accountApi.getByWalletNumber(walletNumber == null ? "" : walletNumber.trim());
        } catch (PayBridgeException ex) {
            throw PayBridgeException.notFound("Recipient wallet not found: " + walletNumber);
        }
        if (wallet.type() != AccountType.CUSTOMER || wallet.customerId() == null || !"NGN".equals(wallet.currency())) {
            throw PayBridgeException.notFound("Recipient wallet not found: " + walletNumber);
        }
        return wallet;
    }

    private AccountDto partnerAccount(UUID partnerId) {
        return accountApi.getByCustomer(partnerId).stream()
                .filter(a -> a.type() == AccountType.PARTNER && "USD".equals(a.currency()))
                .findFirst()
                .orElseThrow(() -> PayBridgeException.notFound("No USD prefund account for this partner"));
    }

    private void requirePartner(AuthenticatedUser user) {
        if (user.role() != Role.PARTNER) {
            throw PayBridgeException.notFound("Not a remittance partner");
        }
        customerApi.requireActive(user.customerId());
    }

    private RemittanceDto replay(Remittance r, String fingerprint) {
        if (!r.getRequestFingerprint().equals(fingerprint)) {
            throw PayBridgeException.conflict("Idempotency-Key was already used for a different remittance");
        }
        return toDto(r);
    }

    private Accounts accounts() {
        return new Accounts(
                gl(InternalAccounts.USD_REMITTANCE_CLEARING),
                gl(InternalAccounts.USD_FX_POSITION),
                gl(InternalAccounts.USD_REMITTANCE_FEE_INCOME),
                gl(InternalAccounts.NGN_FX_POSITION));
    }

    private UUID gl(String walletNumber) {
        return accountApi.getByWalletNumber(walletNumber).id();
    }

    private record Accounts(UUID usdClearing, UUID usdFxPosition, UUID usdFeeIncome, UUID ngnFxPosition) {}

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw PayBridgeException.badRequest("Idempotency-Key header is required");
        }
        if (key.length() > 255) {
            throw PayBridgeException.badRequest("Idempotency-Key must be at most 255 characters");
        }
        return key.trim();
    }

    private static String fingerprint(CreateRemittanceCommand c) {
        String canonical = String.join("|", c.quoteReference(), String.valueOf(c.partnerReference()),
                c.senderName(), c.senderCountry(), c.senderIdNumber(), c.senderAddress(), c.recipientName(),
                c.recipientWalletNumber(), c.purpose());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newReference() {
        return "pb-rem-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private RemittanceDto toDto(Remittance r) {
        return new RemittanceDto(r.getReference(), r.getPartnerReference(), r.getStatus().name(), r.getSenderName(),
                r.getSenderCountry(), r.getRecipientName(), r.getRecipientWalletNumber(), r.getPurpose(),
                r.getSourceAmount(), r.getFee(), r.totalCharged(), r.getSourceCurrency(), r.getRate(),
                r.getTargetAmount(), r.getTargetCurrency(), r.getScreeningNotes(), r.getReviewNote(),
                r.getFailureReason(), r.getCreatedAt(), r.getCompletedAt());
    }
}

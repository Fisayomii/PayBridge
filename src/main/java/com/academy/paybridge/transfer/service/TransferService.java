package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.MovementKind;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.transfer.api.ExternalTransferCommand;
import com.academy.paybridge.transfer.api.InternalTransferCommand;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.Beneficiary;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.domain.TransferType;
import com.academy.paybridge.transfer.repository.TransferRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TransferService implements TransferApi {

    /** Daily limits reset at midnight Lagos time. */
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final Set<TransferStatus> NOT_COUNTED_TOWARDS_LIMITS = EnumSet.of(TransferStatus.FAILED, TransferStatus.REVERSED);
    private static final int MAX_TEXT_LENGTH = 255;
    /** Leaves room for the "Payout failed, refund: " prefix within the 255-char ledger narration. */
    private static final int MAX_PAYOUT_NARRATION_LENGTH = 200;

    private final TransferRepository transferRepository;
    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final LedgerApi ledgerApi;
    private final ComplianceApi complianceApi;
    private final TransferGateway transferGateway;
    private final TransferBookkeeping bookkeeping;
    private final FeeSchedule feeSchedule;
    private final BeneficiaryService beneficiaryService;
    private final PayoutProcessor payoutProcessor;
    private final TransferEventPublisher events;
    private final TransactionTemplate tx;

    public TransferService(TransferRepository transferRepository,
                           AccountApi accountApi,
                           CustomerApi customerApi,
                           LedgerApi ledgerApi,
                           ComplianceApi complianceApi,
                           TransferGateway transferGateway,
                           TransferBookkeeping bookkeeping,
                           FeeSchedule feeSchedule,
                           BeneficiaryService beneficiaryService,
                           PayoutProcessor payoutProcessor,
                           TransferEventPublisher events,
                           TransactionTemplate tx) {
        this.transferRepository = transferRepository;
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.ledgerApi = ledgerApi;
        this.complianceApi = complianceApi;
        this.transferGateway = transferGateway;
        this.bookkeeping = bookkeeping;
        this.feeSchedule = feeSchedule;
        this.beneficiaryService = beneficiaryService;
        this.payoutProcessor = payoutProcessor;
        this.events = events;
        this.tx = tx;
    }

    public TransferGateway.BankResolutionResult resolveBankAccount(String accountNumber, String bankCode) {
        BeneficiaryService.requireNuban(accountNumber, bankCode);
        return transferGateway.resolveBankAccount(accountNumber, bankCode);
    }

    public FeeQuote quote(TransferType type, BigDecimal amount) {
        BigDecimal normalized = normalizeAmount(amount);
        BigDecimal fee = feeSchedule.feeFor(type, normalized);
        return new FeeQuote(type.name(), normalized, fee, normalized.add(fee), "NGN");
    }

    public record FeeQuote(String type, BigDecimal amount, BigDecimal fee, BigDecimal totalDebit, String currency) {}

    // ------------------------------------------------------------------ internal

    @Override
    public TransferDto transferInternal(InternalTransferCommand c) {
        BigDecimal amount = normalizeAmount(c.amount());
        String narration = requireText(c.narration(), "narration", MAX_TEXT_LENGTH);
        String idempotencyKey = requireText(c.idempotencyKey(), "Idempotency-Key", MAX_TEXT_LENGTH);

        CustomerDto sender = customerApi.requireActive(c.initiatorId());
        AccountDto source = requireOwnedWallet(sender.id(), c.sourceWalletNumber());

        UUID beneficiaryId = c.beneficiaryId();
        String destinationNumber = beneficiaryId != null
                ? beneficiaryService.requireOwned(sender.id(), beneficiaryId, TransferType.INTERNAL).getWalletNumber()
                : requireText(c.destinationWalletNumber(), "destinationWalletNumber", MAX_TEXT_LENGTH);
        String fingerprint = fingerprint(TransferType.INTERNAL, source.walletNumber(), destinationNumber, amount, narration);

        // 1. Idempotent replay: a retried request returns the original receipt and never moves money twice.
        Optional<TransferDto> replay = findReplay(source.id(), idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        AccountDto destination = requireRecipientWallet(destinationNumber);
        if (source.id().equals(destination.id())) {
            throw PayBridgeException.badRequest("Source and destination wallets cannot be the same");
        }
        if (!source.currency().equalsIgnoreCase(destination.currency())) {
            throw PayBridgeException.unprocessable("Cross-currency transfers must go through FX ("
                    + source.currency() + " -> " + destination.currency() + ")");
        }
        int receiverTier = customerApi.getById(destination.customerId()).kycTier();
        BigDecimal fee = feeSchedule.feeFor(TransferType.INTERNAL, amount);
        UUID feeIncome = fee.signum() > 0 ? bookkeeping.feeIncomeAccount(source.currency()) : null;

        // 2. Under the sender's row lock: limits, receiver's balance cap, record, move money. All or nothing.
        String reference = newReference();
        Transfer saved;
        try {
            saved = tx.execute(status -> {
                Set<UUID> touched = new HashSet<>(List.of(source.id(), destination.id()));
                if (feeIncome != null) touched.add(feeIncome);
                Map<UUID, AccountDto> locked = accountApi.lockForUpdate(touched);
                screen(sender, locked.get(source.id()), destination.walletNumber(), amount, narration);
                complianceApi.checkInboundCredit(receiverTier, locked.get(destination.id()).balance(), amount);

                Transfer t = transferRepository.saveAndFlush(Transfer.internal(
                        reference, idempotencyKey, fingerprint, sender.id(),
                        source.id(), source.walletNumber(),
                        destination.id(), destination.walletNumber(), destination.ownerName(), beneficiaryId,
                        amount, fee, source.currency(), narration));
                bookkeeping.post(reference, source.id(), destination.id(), amount, source.currency(), narration,
                        MovementKind.CUSTOMER_INITIATED);
                if (feeIncome != null) {
                    bookkeeping.post(reference + TransferBookkeeping.FEE_SUFFIX, source.id(), feeIncome, fee,
                            source.currency(), "Transfer fee: " + reference, MovementKind.CUSTOMER_INITIATED);
                }
                return t;
            });
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request with the same idempotency key won the race.
            return findReplay(source.id(), idempotencyKey, fingerprint).orElseThrow(() -> ex);
        }

        if (c.saveBeneficiary() && beneficiaryId == null) {
            beneficiaryService.rememberInternal(sender.id(), destination.walletNumber(), destination.ownerName());
        }
        events.publish(saved);
        return toDto(saved);
    }

    // ------------------------------------------------------------------ external

    @Override
    public TransferDto transferExternal(ExternalTransferCommand c) {
        BigDecimal amount = normalizeAmount(c.amount());
        String narration = requireText(c.narration(), "narration", MAX_PAYOUT_NARRATION_LENGTH);
        String idempotencyKey = requireText(c.idempotencyKey(), "Idempotency-Key", MAX_TEXT_LENGTH);

        CustomerDto sender = customerApi.requireActive(c.initiatorId());
        AccountDto source = requireOwnedWallet(sender.id(), c.sourceWalletNumber());

        Beneficiary saved = c.beneficiaryId() == null ? null
                : beneficiaryService.requireOwned(sender.id(), c.beneficiaryId(), TransferType.EXTERNAL);
        String accountNumber = saved != null ? saved.getAccountNumber() : c.beneficiaryAccountNumber();
        String bankCode = saved != null ? saved.getBankCode() : c.beneficiaryBankCode();
        BeneficiaryService.requireNuban(accountNumber, bankCode);
        String destination = bankCode + ":" + accountNumber;
        String fingerprint = fingerprint(TransferType.EXTERNAL, source.walletNumber(), destination, amount, narration);

        Optional<TransferDto> replay = findReplay(source.id(), idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        UUID clearingAccountId = bookkeeping.payoutClearingAccount(source.currency());
        BigDecimal fee = feeSchedule.feeFor(TransferType.EXTERNAL, amount);
        UUID feeIncome = bookkeeping.feeIncomeAccount(source.currency());

        // Cheap early exits before calling the provider; authoritative checks repeat under the lock below.
        screen(sender, source, destination, amount, narration);
        if (source.balance().compareTo(amount.add(fee)) < 0) {
            throw PayBridgeException.unprocessable("Insufficient funds in wallet " + source.walletNumber()
                    + ": NGN " + amount.add(fee).toPlainString() + " needed including NGN " + fee.toPlainString() + " fee");
        }

        // 1. Name enquiry + recipient (skipped for saved beneficiaries). No money has moved yet.
        String beneficiaryName;
        String recipientCode;
        if (saved != null) {
            beneficiaryName = saved.getResolvedName();
            recipientCode = saved.getRecipientCode();
        } else {
            beneficiaryName = transferGateway.resolveBankAccount(accountNumber, bankCode).accountName();
            recipientCode = transferGateway.createTransferRecipient(beneficiaryName, accountNumber, bankCode);
        }

        // 2. Hold funds + fee, committed before the provider is called.
        String reference = newReference();
        Transfer held;
        try {
            held = tx.execute(status -> {
                Map<UUID, AccountDto> locked = accountApi.lockForUpdate(List.of(source.id(), clearingAccountId, feeIncome));
                screen(sender, locked.get(source.id()), destination, amount, narration);

                Transfer t = transferRepository.saveAndFlush(Transfer.externalPending(
                        reference, idempotencyKey, fingerprint, sender.id(),
                        source.id(), source.walletNumber(), accountNumber, bankCode,
                        beneficiaryName, recipientCode, saved != null ? saved.getId() : null,
                        amount, fee, source.currency(), narration));
                bookkeeping.post(reference, source.id(), clearingAccountId, amount, source.currency(),
                        "Payout hold: " + narration, MovementKind.CUSTOMER_INITIATED);
                bookkeeping.post(reference + TransferBookkeeping.FEE_SUFFIX, source.id(), feeIncome, fee,
                        source.currency(), "Transfer fee: " + reference, MovementKind.CUSTOMER_INITIATED);
                return t;
            });
        } catch (DataIntegrityViolationException ex) {
            return findReplay(source.id(), idempotencyKey, fingerprint).orElseThrow(() -> ex);
        }
        if (c.saveBeneficiary() && saved == null) {
            beneficiaryService.rememberExternal(sender.id(), accountNumber, bankCode, beneficiaryName, recipientCode);
        }
        events.publish(held);

        // 3. Send to provider outside any transaction; outcome is applied (or left for the reconciler).
        payoutProcessor.dispatch(held);

        return getByReference(reference);
    }

    // ------------------------------------------------------------------ queries

    @Override
    @Transactional(readOnly = true)
    public TransferDto getByReference(String reference) {
        return toDto(find(reference));
    }

    /** The sender (owner of the source wallet) or an admin can read a transfer. Others get 404. */
    @Transactional(readOnly = true)
    public TransferDto getForUser(AuthenticatedUser user, String reference) {
        Transfer t = find(reference);
        if (!user.isAdmin() && !user.customerId().equals(accountApi.getById(t.getSourceAccountId()).customerId())) {
            throw PayBridgeException.notFound("Transfer not found: " + reference);
        }
        return toDto(t);
    }

    @Transactional(readOnly = true)
    public Page<TransferDto> listForUser(AuthenticatedUser user, int page, int size) {
        List<UUID> walletIds = accountApi.getByCustomer(user.customerId()).stream().map(AccountDto::id).toList();
        if (walletIds.isEmpty()) {
            return Page.empty();
        }
        return transferRepository.findBySourceAccountIdInOrderByCreatedAtDesc(walletIds,
                PageRequest.of(page, Math.min(size, 100))).map(this::toDto);
    }

    // ------------------------------------------------------------------ helpers

    /** Computes the sender's usage and asks compliance. Call under the source wallet lock for the authoritative check. */
    private void screen(CustomerDto sender, AccountDto source, String destination, BigDecimal amount, String narration) {
        Instant startOfDay = LocalDate.now(LAGOS).atStartOfDay(LAGOS).toInstant();
        BigDecimal outgoingToday = transferRepository.sumOutgoingSince(source.id(), startOfDay, NOT_COUNTED_TOWARDS_LIMITS);
        long recent = transferRepository.countBySourceAccountIdAndCreatedAtAfter(
                source.id(), Instant.now().minus(complianceApi.velocityWindow()));
        complianceApi.screenTransfer(new ComplianceApi.TransferScreening(
                source.walletNumber(), destination, amount, narration, sender.kycTier(), outgoingToday, recent));
    }

    private Transfer find(String reference) {
        return transferRepository.findByReference(reference)
                .orElseThrow(() -> PayBridgeException.notFound("Transfer not found: " + reference));
    }

    private Optional<TransferDto> findReplay(UUID sourceAccountId, String idempotencyKey, String fingerprint) {
        return transferRepository.findBySourceAccountIdAndIdempotencyKey(sourceAccountId, idempotencyKey)
                .map(existing -> {
                    if (existing.getRequestFingerprint() != null
                            && !existing.getRequestFingerprint().equals(fingerprint)) {
                        throw PayBridgeException.conflict(
                                "Idempotency-Key '" + idempotencyKey + "' was already used for a different request");
                    }
                    return toDto(existing);
                });
    }

    /** The caller's own wallet. Someone else's wallet is reported as not found, never as forbidden. */
    private AccountDto requireOwnedWallet(UUID customerId, String walletNumber) {
        if (walletNumber == null || walletNumber.isBlank()) {
            throw PayBridgeException.badRequest("sourceWalletNumber is required");
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

    private AccountDto requireRecipientWallet(String walletNumber) {
        AccountDto wallet = accountApi.getByWalletNumber(walletNumber.trim());
        if (wallet.type() != AccountType.CUSTOMER || wallet.customerId() == null) {
            // GL accounts and legacy ownerless wallets are not valid recipients.
            throw PayBridgeException.notFound("Wallet not found: " + walletNumber);
        }
        return wallet;
    }

    private TransferDto toDto(Transfer t) {
        return new TransferDto(
                t.getId(),
                t.getReference(),
                t.getType().name(),
                t.getStatus().name(),
                t.getSourceWalletNumber(),
                t.getDestinationWalletNumber(),
                TransferEventPublisher.maskAccountNumber(t.getBeneficiaryAccountNumber()),
                t.getBeneficiaryBankCode(),
                t.getResolvedBeneficiaryName(),
                t.getAmount(),
                t.getFee(),
                t.getAmount().add(t.getFee()),
                t.getCurrency(),
                t.getNarration(),
                t.getFailureReason(),
                t.getGatewayTransferCode(),
                accountApi.getById(t.getSourceAccountId()).balance(),
                ledgerApi.getEntriesByReferences(TransferBookkeeping.allEntryReferences(t.getReference())),
                t.getCreatedAt(),
                t.getCompletedAt()
        );
    }

    /** Paystack requires 16-50 chars of lowercase alphanumerics, '-' or '_'. */
    static String newReference() {
        return "pb-trf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
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

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw PayBridgeException.badRequest(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw PayBridgeException.badRequest(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String fingerprint(TransferType type, String source, String destination,
                                      BigDecimal amount, String narration) {
        String canonical = String.join("|", type.name(), source, destination, amount.toPlainString(), narration);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

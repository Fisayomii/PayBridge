package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.settlement.domain.ReconciliationBreak;
import com.academy.paybridge.settlement.domain.ReconciliationBreak.Resolution;
import com.academy.paybridge.settlement.domain.ReconciliationRun;
import com.academy.paybridge.settlement.repository.ReconciliationBreakRepository;
import com.academy.paybridge.settlement.repository.ReconciliationRunRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.api.PayoutReconciliationApi;
import com.academy.paybridge.transfer.api.PayoutReconciliationApi.PayoutCheck;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checks that the books agree with themselves (integrity) and with the payment provider (reconciliation).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LogManager.getLogger(ReconciliationService.class);
    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final AccountApi accountApi;
    private final PayoutReconciliationApi payoutApi;
    private final RemittanceApi remittanceApi;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationBreakRepository breakRepository;

    public ReconciliationService(AccountApi accountApi, PayoutReconciliationApi payoutApi, RemittanceApi remittanceApi,
                                 ReconciliationRunRepository runRepository,
                                 ReconciliationBreakRepository breakRepository) {
        this.accountApi = accountApi;
        this.payoutApi = payoutApi;
        this.remittanceApi = remittanceApi;
        this.runRepository = runRepository;
        this.breakRepository = breakRepository;
    }

    // ------------------------------------------------------------------ trial balance and integrity

    @Transactional(readOnly = true)
    public TrialBalance trialBalance() {
        Map<String, List<AccountLine>> byCurrency = new LinkedHashMap<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (AccountDto a : accountApi.listAll()) {
            byCurrency.computeIfAbsent(a.currency(), c -> new ArrayList<>())
                    .add(new AccountLine(a.walletNumber(), a.ownerName(), a.type().name(), a.balance()));
            totals.merge(a.currency(), a.balance(), BigDecimal::add);
        }
        List<CurrencySection> sections = byCurrency.entrySet().stream()
                .map(e -> new CurrencySection(e.getKey(), totals.get(e.getKey()),
                        totals.get(e.getKey()).signum() == 0, e.getValue()))
                .toList();
        return new TrialBalance(Instant.now(), sections);
    }

    public record TrialBalance(Instant asOf, List<CurrencySection> currencies) {}

    /** total must be exactly zero for each currency: every posting is balanced within one currency. */
    public record CurrencySection(String currency, BigDecimal total, boolean balanced, List<AccountLine> accounts) {}

    public record AccountLine(String accountNumber, String name, String type, BigDecimal balance) {}

    /**
     * The checks an operations team runs every night before trusting the day's numbers. Each one compares a
     * balance with the business records that should explain it.
     */
    @Transactional(readOnly = true)
    public IntegrityReport integrityCheck() {
        List<Check> checks = new ArrayList<>();
        List<AccountDto> accounts = accountApi.listAll();

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        accounts.forEach(a -> totals.merge(a.currency(), a.balance(), BigDecimal::add));
        totals.forEach((ccy, total) -> checks.add(new Check("Ledger balances in " + ccy,
                "Every " + ccy + " account sums to zero", BigDecimal.ZERO, total, total.signum() == 0)));

        long overdrawn = accounts.stream()
                .filter(a -> a.type() != AccountType.INTERNAL && a.balance().signum() < 0).count();
        checks.add(new Check("No overdrawn wallets", "Customer and partner accounts are never negative",
                BigDecimal.ZERO, BigDecimal.valueOf(overdrawn), overdrawn == 0));

        BigDecimal payoutClearing = balance(accounts, InternalAccounts.NGN_PAYOUT_CLEARING);
        BigDecimal openPayouts = payoutApi.openPayoutTotal("NGN");
        checks.add(new Check("Payout clearing", "GL-NGN-PAYOUT-CLEARING equals bank payouts still in flight",
                openPayouts, payoutClearing, payoutClearing.compareTo(openPayouts) == 0));

        BigDecimal remittanceClearing = balance(accounts, InternalAccounts.USD_REMITTANCE_CLEARING);
        BigDecimal held = remittanceApi.heldForReviewTotal();
        checks.add(new Check("Remittance clearing",
                "GL-USD-REMITTANCE-CLEARING equals remittances waiting for compliance review",
                held, remittanceClearing, remittanceClearing.compareTo(held) == 0));

        long openBreaks = breakRepository.findByStatusOrderByCreatedAtAsc(ReconciliationBreak.Status.OPEN).size();
        checks.add(new Check("Open reconciliation breaks", "Mismatches with the provider still being investigated",
                BigDecimal.ZERO, BigDecimal.valueOf(openBreaks), openBreaks == 0));

        boolean healthy = checks.stream().allMatch(Check::passed);
        return new IntegrityReport(Instant.now(), healthy, checks);
    }

    public record IntegrityReport(Instant asOf, boolean healthy, List<Check> checks) {}

    public record Check(String name, String rule, BigDecimal expected, BigDecimal actual, boolean passed) {}

    // ------------------------------------------------------------------ payout reconciliation

    /**
     * Compares every bank payout created on {@code businessDate} (Lagos time) with the provider's record.
     * Disagreements become breaks; a break that is already open is not duplicated.
     */
    public PayoutReconciliationResult reconcilePayouts(LocalDate businessDate, UUID triggeredBy) {
        Instant from = businessDate.atStartOfDay(LAGOS).toInstant();
        Instant to = businessDate.plusDays(1).atStartOfDay(LAGOS).toInstant();
        ReconciliationRun run = new ReconciliationRun(ReconciliationRun.PAYOUTS, businessDate, triggeredBy, Instant.now());

        List<PayoutCheck> checks = payoutApi.checkPayoutsWithProvider(from, to);
        int matched = 0;
        int unavailable = 0;
        List<ReconciliationBreak> found = new ArrayList<>();
        for (PayoutCheck c : checks) {
            if ("UNAVAILABLE".equals(c.providerStatus())) {
                unavailable++;
                continue;
            }
            ReconciliationBreak.Type type = classify(c);
            if (type == null) {
                matched++;
            } else if (!breakRepository.existsByReferenceAndStatus(c.reference(), ReconciliationBreak.Status.OPEN)) {
                found.add(new ReconciliationBreak(run.getId(), type, c.reference(), c.amount(), c.ledgerStatus(),
                        c.providerStatus(), "Ledger says " + c.ledgerStatus() + ", provider says "
                        + c.providerStatus() + (c.providerMessage() == null ? "" : " (" + c.providerMessage() + ")")));
            }
        }
        run.finish(checks.size(), found.size());
        runRepository.save(run);
        breakRepository.saveAll(found);
        if (!found.isEmpty()) {
            log.error("RECONCILIATION {} payout breaks for {}: {}", found.size(), businessDate,
                    found.stream().map(ReconciliationBreak::getReference).toList());
        }
        return new PayoutReconciliationResult(run.getId(), businessDate, checks.size(), matched, unavailable,
                found.size(), found);
    }

    public record PayoutReconciliationResult(UUID runId, LocalDate businessDate, int checked, int matched,
                                             int providerUnavailable, int newBreaks,
                                             List<ReconciliationBreak> breaks) {}

    /** Null when ledger and provider agree. */
    static ReconciliationBreak.Type classify(PayoutCheck c) {
        String ledger = c.ledgerStatus();
        String provider = c.providerStatus();
        if ("NOT_FOUND".equals(provider)) {
            // A payout the provider rejected outright never existed there; we already refunded it.
            return "FAILED".equals(ledger) ? null : ReconciliationBreak.Type.MISSING_AT_PROVIDER;
        }
        String ledgerAsProvider = switch (ledger) {
            case "PENDING", "PROCESSING" -> "PENDING";
            default -> ledger;
        };
        return ledgerAsProvider.equals(provider) ? null : ReconciliationBreak.Type.STATUS_MISMATCH;
    }

    @Transactional(readOnly = true)
    public List<ReconciliationBreak> listBreaks(ReconciliationBreak.Status status) {
        return status == null ? breakRepository.findAllByOrderByCreatedAtDesc()
                : breakRepository.findByStatusOrderByCreatedAtAsc(status);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRun> listRuns(int limit) {
        return runRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, Math.min(limit, 100)));
    }

    /**
     * ACCEPT_PROVIDER pushes the provider's outcome through the normal payout state machine, so a missed
     * reversal refunds the customer exactly as the webhook would have. CLOSED_WITH_NOTE moves no money.
     */
    public ReconciliationBreak resolveBreak(UUID breakId, Resolution resolution, String note, UUID adminId) {
        ReconciliationBreak b = breakRepository.findById(breakId)
                .orElseThrow(() -> PayBridgeException.notFound("Break not found: " + breakId));
        if (b.getStatus() == ReconciliationBreak.Status.RESOLVED) {
            throw PayBridgeException.conflict("Break " + breakId + " is already resolved");
        }
        if (resolution == Resolution.ACCEPT_PROVIDER) {
            payoutApi.acceptProviderStatus(b.getReference());
        }
        b.resolve(resolution, note, adminId);
        log.info("AUDIT break {} on {} resolved {} by {}: {}", breakId, b.getReference(), resolution, adminId, note);
        return breakRepository.save(b);
    }

    private static BigDecimal balance(List<AccountDto> accounts, String walletNumber) {
        return accounts.stream().filter(a -> a.walletNumber().equals(walletNumber))
                .map(AccountDto::balance).findFirst().orElse(BigDecimal.ZERO);
    }
}

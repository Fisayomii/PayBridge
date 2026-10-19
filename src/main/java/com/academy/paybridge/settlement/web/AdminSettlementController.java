package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.domain.FxDeal;
import com.academy.paybridge.settlement.domain.ReconciliationBreak;
import com.academy.paybridge.settlement.domain.ReconciliationRun;
import com.academy.paybridge.settlement.service.LiquidityService;
import com.academy.paybridge.settlement.service.LiquidityService.LiquidityView;
import com.academy.paybridge.settlement.service.ReconciliationService;
import com.academy.paybridge.settlement.service.ReconciliationService.IntegrityReport;
import com.academy.paybridge.settlement.service.ReconciliationService.PayoutReconciliationResult;
import com.academy.paybridge.settlement.service.ReconciliationService.TrialBalance;
import com.academy.paybridge.settlement.service.TreasuryService;
import com.academy.paybridge.settlement.service.TreasuryService.FxPosition;
import com.academy.paybridge.settlement.service.TreasuryService.PrefundView;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/settlement")
@Tag(name = "11. Admin: settlement", description = "Prefunds, FX deals, trial balance, integrity checks and reconciliation (ROLE_ADMIN)")
public class AdminSettlementController {

    private final TreasuryService treasuryService;
    private final ReconciliationService reconciliationService;
    private final LiquidityService liquidityService;

    public AdminSettlementController(TreasuryService treasuryService, ReconciliationService reconciliationService,
                                     LiquidityService liquidityService) {
        this.treasuryService = treasuryService;
        this.reconciliationService = reconciliationService;
        this.liquidityService = liquidityService;
    }

    @GetMapping("/liquidity")
    @Operation(summary = "Live payout-provider balance (Paystack GET /balance) vs bank payouts in flight: OK, LOW or INSUFFICIENT")
    public LiquidityView liquidity() {
        return liquidityService.ngnLiquidity();
    }

    @PostMapping("/prefunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Book a partner's USD wire seen on our bank statement (idempotent on bankReference)")
    public PrefundView recordPrefund(@Valid @RequestBody PrefundRequest request) {
        return treasuryService.recordPrefund(request.partnerAccountNumber(), request.amount(),
                request.bankReference(), CurrentUser.require().customerId());
    }

    @GetMapping("/prefunds")
    @Operation(summary = "Booked partner prefunds, newest first")
    public List<PrefundView> prefunds() {
        return treasuryService.listPrefunds();
    }

    @GetMapping("/fx-position")
    @Operation(summary = "Open USD position, NGN paid out against it, and the unrealised gain/loss at today's mid rate")
    public FxPosition fxPosition() {
        return treasuryService.position();
    }

    @PostMapping("/fx-deals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Book the sale of the whole open USD position at the bank's executed rate; realises the gain/loss")
    public FxDeal bookFxDeal(@Valid @RequestBody FxDealRequest request) {
        return treasuryService.bookFxDeal(request.executedRate(), request.bankReference(),
                CurrentUser.require().customerId());
    }

    @GetMapping("/fx-deals")
    @Operation(summary = "Booked FX deals, newest first")
    public List<FxDeal> fxDeals() {
        return treasuryService.listDeals();
    }

    @GetMapping("/trial-balance")
    @Operation(summary = "Every account by currency; each currency must total exactly zero")
    public TrialBalance trialBalance() {
        return reconciliationService.trialBalance();
    }

    @GetMapping("/integrity-check")
    @Operation(summary = "Run the end-of-day checks: zero-sum per currency, clearing accounts explained, no open breaks")
    public IntegrityReport integrityCheck() {
        return reconciliationService.integrityCheck();
    }

    @PostMapping("/reconciliations/payouts")
    @Operation(summary = "Compare every bank payout of a Lagos business day with the provider; mismatches become breaks")
    public PayoutReconciliationResult reconcilePayouts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(ReconciliationService.LAGOS);
        return reconciliationService.reconcilePayouts(day, CurrentUser.require().customerId());
    }

    @GetMapping("/reconciliations")
    @Operation(summary = "Recent reconciliation runs")
    public List<ReconciliationRun> runs(@RequestParam(defaultValue = "20") int limit) {
        return reconciliationService.listRuns(Math.max(limit, 1));
    }

    @GetMapping("/breaks")
    @Operation(summary = "Reconciliation breaks; status=OPEN is the work queue")
    public List<ReconciliationBreak> breaks(@RequestParam(required = false) ReconciliationBreak.Status status) {
        return reconciliationService.listBreaks(status);
    }

    @PostMapping("/breaks/{breakId}/resolve")
    @Operation(summary = "Resolve a break: ACCEPT_PROVIDER applies the provider's status (refunds included); CLOSED_WITH_NOTE moves no money")
    public ReconciliationBreak resolve(@PathVariable UUID breakId, @Valid @RequestBody ResolveBreakRequest request) {
        return reconciliationService.resolveBreak(breakId, request.resolution(), request.note(),
                CurrentUser.require().customerId());
    }

    public record PrefundRequest(
            @Schema(example = "PARTNER-USD-123456") @NotBlank String partnerAccountNumber,
            @Schema(example = "5000.00") @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Schema(example = "FED-WIRE-20261015-0001") @NotBlank @Size(max = 100) String bankReference
    ) {}

    public record FxDealRequest(
            @Schema(example = "1565.50") @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 8) BigDecimal executedRate,
            @Schema(example = "DEAL-20261015-01") @NotBlank @Size(max = 100) String bankReference
    ) {}

    public record ResolveBreakRequest(
            @Schema(example = "ACCEPT_PROVIDER") @NotNull ReconciliationBreak.Resolution resolution,
            @Schema(example = "Reversal webhook was lost; provider confirms funds returned") @NotBlank @Size(max = 500) String note
    ) {}
}

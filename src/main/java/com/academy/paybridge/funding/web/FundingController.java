package com.academy.paybridge.funding.web;

import com.academy.paybridge.funding.service.FundingService;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/fundings")
@Tag(name = "4. Wallet funding", description = "Top up your wallet through Paystack checkout")
public class FundingController {

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a top-up: returns a Paystack checkout URL",
            description = "Open authorizationUrl to pay. The wallet is credited only after PayBridge verifies the "
                    + "payment with Paystack (via webhook, POST /{reference}/verify, or background reconciliation).")
    public FundingService.FundingDto startCheckout(
            @Parameter(example = "topup-2026-10-12-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        return fundingService.startCheckout(CurrentUser.require(), request.walletNumber(), request.amount(), idempotencyKey);
    }

    @PostMapping("/{reference}/verify")
    @Operation(summary = "Check a top-up with Paystack now (call after returning from checkout)")
    public FundingService.FundingDto verify(@PathVariable String reference) {
        return fundingService.verifyForUser(CurrentUser.require(), reference);
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Get one of your top-ups")
    public FundingService.FundingDto get(@PathVariable String reference) {
        return fundingService.getForUser(CurrentUser.require(), reference);
    }

    @GetMapping
    @Operation(summary = "Top-ups and adjustments on one of your wallets")
    public List<FundingService.FundingDto> list(@RequestParam String walletNumber,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return fundingService.listForWallet(CurrentUser.require(), walletNumber, Math.max(page, 0), Math.max(size, 1));
    }

    public record CheckoutRequest(
            @Schema(example = "9012345678") @NotBlank String walletNumber,
            @Schema(example = "20000.00") @NotNull @DecimalMin("100.00") @Digits(integer = 17, fraction = 2) BigDecimal amount
    ) {}
}

package com.academy.paybridge.funding.web;

import com.academy.paybridge.funding.domain.FundingDirection;
import com.academy.paybridge.funding.service.FundingService;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/admin/accounts/{walletNumber}/adjustments")
@Tag(name = "9. Admin: wallets", description = "Operations-only wallet controls (ROLE_ADMIN)")
public class AdminFundingController {

    private final FundingService fundingService;

    public AdminFundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Manually credit or debit a wallet (ledgered against GL-NGN-ADJUSTMENTS, audited)")
    public FundingService.FundingDto adjust(@PathVariable String walletNumber,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody AdjustmentRequest request) {
        return fundingService.adjust(CurrentUser.require(), walletNumber, request.direction(), request.amount(),
                request.reason(), idempotencyKey);
    }

    public record AdjustmentRequest(
            @Schema(example = "CREDIT") @NotNull FundingDirection direction,
            @Schema(example = "50000.00") @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Schema(example = "Sandbox test funds, ticket OPS-7") @NotBlank @Size(max = 500) String reason
    ) {}
}

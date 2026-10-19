package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.service.FxService;
import com.academy.paybridge.fx.service.FxService.RateCard;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@Tag(name = "5. FX rates", description = "USD/NGN reference rate, spread and fee")
public class FxController {

    private final FxService fxService;

    public FxController(FxService fxService) {
        this.fxService = fxService;
    }

    @GetMapping("/api/v1/fx/rates")
    @Operation(summary = "Current USD/NGN rate card: mid rate, the rate recipients get, spread, fee, quote validity")
    public RateCard rateCard() {
        return fxService.rateCard();
    }

    @PutMapping("/api/v1/admin/fx/rates")
    @Tag(name = "10. Admin: FX and remittances")
    @Operation(summary = "Publish a new mid rate (the simulated rate feed). Moves over the deviation limit need force=true")
    public RateCard publish(@Valid @RequestBody PublishRateRequest request) {
        return fxService.publishMidRate(request.midRate(), CurrentUser.require().customerId(),
                Boolean.TRUE.equals(request.force()));
    }

    @GetMapping("/api/v1/admin/fx/rates/history")
    @Tag(name = "10. Admin: FX and remittances")
    @Operation(summary = "Published mid rates, newest first")
    public List<FxRate> history(@RequestParam(defaultValue = "20") int limit) {
        return fxService.rateHistory(Math.max(limit, 1));
    }

    public record PublishRateRequest(
            @Schema(example = "1580.00") @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 8) BigDecimal midRate,
            @Schema(example = "false") Boolean force
    ) {}
}

package com.academy.paybridge.remittance.web;

import com.academy.paybridge.fx.api.QuoteDto;
import com.academy.paybridge.ledger.api.LedgerEntryDto;
import com.academy.paybridge.remittance.api.RemittanceDto;
import com.academy.paybridge.remittance.service.RemittanceService;
import com.academy.paybridge.remittance.service.RemittanceService.CreateRemittanceCommand;
import com.academy.paybridge.remittance.service.RemittanceService.PartnerAccountView;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * The API a licensed money transfer operator abroad integrates with. Partners log in with the same
 * /api/v1/auth/login as everyone else and get a token with role PARTNER.
 */
@RestController
@RequestMapping("/api/v1/partner")
@Tag(name = "6. Partner API (remittance)", description = "For licensed money transfer partners abroad (ROLE_PARTNER)")
public class PartnerRemittanceController {

    private final RemittanceService remittanceService;

    public PartnerRemittanceController(RemittanceService remittanceService) {
        this.remittanceService = remittanceService;
    }

    @GetMapping("/account")
    @Operation(summary = "Your USD prefund balance")
    public PartnerAccountView account() {
        return remittanceService.partnerAccountView(CurrentUser.require());
    }

    @GetMapping("/account/statement")
    @Operation(summary = "Ledger lines on your prefund account, newest first")
    public List<LedgerEntryDto> statement(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return remittanceService.partnerStatement(CurrentUser.require(), Math.max(page, 0), Math.max(size, 1));
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Lock a USD -> NGN rate. The quote is valid for a short time and for one remittance")
    public QuoteDto quote(@Valid @RequestBody QuoteRequest request) {
        return remittanceService.quote(CurrentUser.require(), request.amountUsd());
    }

    @PostMapping("/remittances")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a remittance against a quote. COMPLETED = wallet credited; PENDING_REVIEW = held "
            + "for compliance; REJECTED = blocked, nothing charged")
    public RemittanceDto create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                @Valid @RequestBody CreateRemittanceRequest request) {
        return remittanceService.create(CurrentUser.require(), new CreateRemittanceCommand(
                request.quoteReference(), request.partnerReference(), request.senderName(), request.senderCountry(),
                request.senderIdNumber(), request.senderAddress(), request.recipientName(),
                request.recipientWalletNumber(), request.purpose()), idempotencyKey);
    }

    @GetMapping("/remittances")
    @Operation(summary = "Your remittances, newest first")
    public Page<RemittanceDto> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return remittanceService.listForPartner(CurrentUser.require(), Math.max(page, 0), Math.max(size, 1));
    }

    @GetMapping("/remittances/{reference}")
    @Operation(summary = "One remittance's status")
    public RemittanceDto get(@PathVariable String reference) {
        return remittanceService.getForPartner(CurrentUser.require(), reference);
    }

    public record QuoteRequest(
            @Schema(example = "200.00") @NotNull @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal amountUsd
    ) {}

    /** Sender fields are the FATF travel-rule data the partner collected during its own KYC. */
    public record CreateRemittanceRequest(
            @Schema(example = "pb-qte-...") @NotBlank String quoteReference,
            @Schema(example = "DR-TX-55821", description = "The partner's own id for this transfer")
            @Size(max = 100) String partnerReference,
            @Schema(example = "Grace Okafor") @NotBlank @Size(max = 255) String senderName,
            @Schema(example = "US") @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO 3166 alpha-2 code")
            String senderCountry,
            @Schema(example = "US-DL-D1234567") @NotBlank @Size(max = 100) String senderIdNumber,
            @Schema(example = "12 Main St, Houston, TX") @NotBlank @Size(max = 500) String senderAddress,
            @Schema(example = "Ada Obi") @NotBlank @Size(max = 255) String recipientName,
            @Schema(example = "9123456789") @NotBlank @Size(max = 50) String recipientWalletNumber,
            @Schema(example = "Family support") @NotBlank @Size(max = 100) String purpose
    ) {}
}

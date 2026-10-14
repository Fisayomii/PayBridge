package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.CurrentUser;
import com.academy.paybridge.transfer.api.ExternalTransferCommand;
import com.academy.paybridge.transfer.api.InternalTransferCommand;
import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.TransferType;
import com.academy.paybridge.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "2. Transfers", description = "Wallet-to-wallet transfers and payouts to external bank accounts")
public class TransferController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/banks/resolve")
    @Operation(summary = "Name enquiry: resolve a NUBAN to the account holder's name before an external transfer")
    public TransferGateway.BankResolutionResult resolveAccount(
            @Parameter(description = "10-digit NUBAN account number", example = "0001234567")
            @RequestParam String accountNumber,
            @Parameter(description = "Bank code (e.g. 058 for GTBank)", example = "058")
            @RequestParam String bankCode
    ) {
        CurrentUser.require();
        return transferService.resolveBankAccount(accountNumber, bankCode);
    }

    @GetMapping("/fees")
    @Operation(summary = "Quote the fee for a transfer before sending it")
    public TransferService.FeeQuote quoteFee(@RequestParam TransferType type, @RequestParam BigDecimal amount) {
        return transferService.quote(type, amount);
    }

    @PostMapping("/internal")
    @Operation(summary = "Send money to another PayBridge wallet (instant, atomic, never touches Paystack)",
            description = "Give either destinationWalletNumber or a saved INTERNAL beneficiaryId.")
    public ResponseEntity<TransferDto> transferInternal(
            @Parameter(description = "Unique per sending wallet; retrying with the same key returns the original transfer",
                    example = "7f3c0e2a-internal-001")
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody InternalTransferRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        TransferDto dto = transferService.transferInternal(new InternalTransferCommand(
                user.customerId(),
                request.sourceWalletNumber(),
                request.destinationWalletNumber(),
                request.beneficiaryId(),
                request.amount(),
                request.narration(),
                idempotencyKey,
                Boolean.TRUE.equals(request.saveBeneficiary())));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PostMapping("/external")
    @Operation(summary = "Pay out from your wallet to a Nigerian bank account via Paystack",
            description = "Give either beneficiaryAccountNumber + beneficiaryBankCode or a saved EXTERNAL beneficiaryId. "
                    + "Amount + fee are held immediately. Returns 202 with status PENDING/PROCESSING; the final status "
                    + "(SUCCESS, FAILED with automatic refund of amount and fee, or REVERSED) arrives via webhook or "
                    + "reconciliation. Poll GET /api/v1/transfers/{reference} for the outcome.")
    public ResponseEntity<TransferDto> transferExternal(
            @Parameter(description = "Unique per sending wallet; retrying with the same key returns the original transfer",
                    example = "7f3c0e2a-payout-001")
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody ExternalTransferRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        TransferDto dto = transferService.transferExternal(new ExternalTransferCommand(
                user.customerId(),
                request.sourceWalletNumber(),
                request.beneficiaryAccountNumber(),
                request.beneficiaryBankCode(),
                request.beneficiaryId(),
                request.amount(),
                request.narration(),
                idempotencyKey,
                Boolean.TRUE.equals(request.saveBeneficiary())));
        HttpStatus status = "SUCCESS".equals(dto.status()) ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(dto);
    }

    @GetMapping
    @Operation(summary = "Transfers you have sent, newest first (incoming money is on your wallet statement)")
    public Page<TransferDto> myTransfers(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return transferService.listForUser(CurrentUser.require(), Math.max(page, 0), Math.max(size, 1));
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Get one of your transfers with its current status and ledger lines")
    public TransferDto getTransferByReference(@PathVariable String reference) {
        return transferService.getForUser(CurrentUser.require(), reference);
    }

    public record InternalTransferRequest(
            @Schema(example = "9012345678")
            @NotBlank String sourceWalletNumber,

            @Schema(example = "9087654321", description = "Omit when using beneficiaryId")
            String destinationWalletNumber,

            @Schema(description = "Saved INTERNAL beneficiary; alternative to destinationWalletNumber")
            UUID beneficiaryId,

            @Schema(example = "15000.00")
            @NotNull @DecimalMin("1.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,

            @Schema(example = "Payment for invoice #402")
            @NotBlank @Size(max = 255) String narration,

            @Schema(description = "Save the recipient as a beneficiary", example = "false")
            Boolean saveBeneficiary
    ) {}

    public record ExternalTransferRequest(
            @Schema(example = "9012345678")
            @NotBlank String sourceWalletNumber,

            @Schema(example = "0001234567", description = "Omit when using beneficiaryId")
            @Pattern(regexp = "\\d{10}", message = "must be a 10-digit NUBAN") String beneficiaryAccountNumber,

            @Schema(example = "058", description = "Omit when using beneficiaryId")
            @Pattern(regexp = "\\d{3,6}", message = "must be 3-6 digits") String beneficiaryBankCode,

            @Schema(description = "Saved EXTERNAL beneficiary; alternative to account number + bank code")
            UUID beneficiaryId,

            @Schema(example = "15000.00")
            @NotNull @DecimalMin("100.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,

            @Schema(example = "Rent for October")
            @NotBlank @Size(max = 200) String narration,

            @Schema(description = "Save the bank account as a beneficiary", example = "false")
            Boolean saveBeneficiary
    ) {}
}

package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "2. Transfers (Paystack Sandbox + Wallet-to-Wallet)", description = "Resolve bank accounts, transfer funds between wallets, and handle webhooks")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @GetMapping("/banks/resolve")
    @Operation(summary = "Step 1: Name Enquiry via Paystack Sandbox (/bank/resolve)")
    public TransferGateway.BankResolutionResult resolveAccount(
            @Parameter(description = "10-digit NUBAN account number", example = "0000000000")
            @RequestParam String accountNumber,
            @Parameter(description = "CBN Bank Code (e.g., 058 for GTBank)", example = "058")
            @RequestParam String bankCode
    ) {
        return transferService.resolveBankAccount(accountNumber, bankCode);
    }

    @PostMapping
    @Operation(summary = "Step 2: Initiate Wallet-to-Wallet Transfer (Debits Sender, Calls Paystack Sandbox, Credits Receiver & Logs Double-Entry Ledger)")
    public TransferDto initiateTransfer(@Valid @RequestBody InitiateTransferRequest request) {
        return transferService.transferBetweenWallets(
                request.sourceWalletNumber(),
                request.destinationWalletNumber(),
                request.amount(),
                request.narration(),
                request.idempotencyKey()
        );
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Step 3: Get Transfer Receipt, Updated Wallet Balances & Double-Entry Ledger Lines")
    public TransferDto getTransferByReference(
            @Parameter(description = "Transfer reference (e.g. PB-TRF-XXXXXXXX)")
            @PathVariable String reference
    ) {
        return transferService.getByReference(reference);
    }

    @PostMapping("/webhooks/paystack")
    @Operation(summary = "Step 4: Simulate Paystack Webhook (transfer.success or transfer.failed with automatic reversal)")
    public TransferDto simulatePaystackWebhook(@Valid @RequestBody WebhookSimulationRequest request) {
        return transferService.handleWebhookUpdate(request.reference(), request.event());
    }

    public record InitiateTransferRequest(
            @Schema(example = "WALLET-1001")
            @NotBlank String sourceWalletNumber,

            @Schema(example = "WALLET-2002")
            @NotBlank String destinationWalletNumber,

            @Schema(example = "15000.00")
            @NotNull @DecimalMin("100.00") BigDecimal amount,

            @Schema(example = "Payment for invoice #402")
            @NotBlank String narration,

            @Schema(example = "IDEMP-2026-001")
            @NotBlank String idempotencyKey
    ) {}

    public record WebhookSimulationRequest(
            @Schema(example = "PB-TRF-12345678")
            @NotBlank String reference,

            @Schema(example = "transfer.failed", description = "Use 'transfer.success' or 'transfer.failed' (triggers automatic reversal!)")
            @NotBlank String event
    ) {}
}
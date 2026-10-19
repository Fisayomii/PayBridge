package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.SimulatedTransferGateway;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutResult;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutStatus;
import com.academy.paybridge.transfer.service.PayoutProcessor;
import com.academy.paybridge.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * Sandbox-only, admin-only: stands in for Paystack's transfer webhook so payout outcomes can be demoed
 * without ngrok. Not registered at all when the real Paystack provider is active.
 */
@RestController
@RequestMapping("/api/v1/admin/simulations/transfers")
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "simulated")
@Tag(name = "8. Simulations (sandbox only)", description = "Drive provider outcomes without Paystack")
public class TransferSimulationController {

    private final SimulatedTransferGateway gateway;
    private final PayoutProcessor payoutProcessor;
    private final TransferService transferService;

    public TransferSimulationController(SimulatedTransferGateway gateway,
                                        PayoutProcessor payoutProcessor,
                                        TransferService transferService) {
        this.gateway = gateway;
        this.payoutProcessor = payoutProcessor;
        this.transferService = transferService;
    }

    @PostMapping("/{reference}/webhook")
    @Operation(summary = "Simulate a payout webhook (transfer.success, transfer.failed -> refund, transfer.reversed -> refund). "
            + "sendWebhook=false changes only the provider's record, as if the webhook was lost: reconciliation then finds the break")
    public TransferDto simulateWebhook(@PathVariable String reference,
                                       @Valid @RequestBody SimulatedWebhookRequest request) {
        PayoutStatus status = switch (request.event()) {
            case "transfer.success" -> PayoutStatus.SUCCESS;
            case "transfer.failed" -> PayoutStatus.FAILED;
            default -> PayoutStatus.REVERSED;
        };
        PayoutResult result = gateway.settle(reference, status, "Simulated " + request.event());
        if (!Boolean.FALSE.equals(request.sendWebhook())) {
            payoutProcessor.applyGatewayResult(reference, result.status(), result.gatewayReference(), result.message());
        }
        return transferService.getByReference(reference);
    }

    public record SimulatedWebhookRequest(
            @Schema(example = "transfer.failed")
            @NotBlank
            @Pattern(regexp = "transfer\\.(success|failed|reversed)")
            String event,
            @Schema(example = "true", description = "false = provider changes state but the webhook never arrives")
            Boolean sendWebhook
    ) {}
}

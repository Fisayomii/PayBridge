package com.academy.paybridge.funding.web;

import com.academy.paybridge.funding.client.SimulatedCollectionGateway;
import com.academy.paybridge.funding.service.FundingService;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * Sandbox-only: stands in for the customer paying on Paystack's checkout page, then delivers the
 * equivalent of the charge.success webhook. Not registered when the real Paystack provider is active.
 */
@RestController
@RequestMapping("/api/v1/fundings/simulations")
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "simulated")
@Tag(name = "8. Simulations (sandbox only)", description = "Drive provider outcomes without Paystack")
public class FundingSimulationController {

    private final SimulatedCollectionGateway gateway;
    private final FundingService fundingService;

    public FundingSimulationController(SimulatedCollectionGateway gateway, FundingService fundingService) {
        this.gateway = gateway;
        this.fundingService = fundingService;
    }

    @PostMapping("/{reference}/pay")
    @Operation(summary = "Simulate paying (or failing to pay) one of your checkouts, then confirm it")
    public FundingService.FundingDto pay(@PathVariable String reference, @RequestBody(required = false) PayRequest request) {
        fundingService.getForUser(CurrentUser.require(), reference); // ownership check
        boolean success = request == null || request.success() == null || request.success();
        gateway.pay(reference, success, request == null ? null : request.amountMinorOverride());
        return fundingService.confirmCheckout(reference);
    }

    public record PayRequest(
            @Schema(example = "true") Boolean success,
            @Schema(description = "Test tampering: pretend the provider charged a different amount (kobo)")
            Long amountMinorOverride
    ) {}
}

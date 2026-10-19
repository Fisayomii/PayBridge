package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.service.KycService;
import com.academy.paybridge.customer.service.KycService.VerificationResult;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kyc")
@Tag(name = "0. Authentication", description = "Register, log in, verify your identity")
public class KycController {

    private final KycService kycService;

    public KycController(KycService kycService) {
        this.kycService = kycService;
    }

    @PostMapping("/bvn")
    @Operation(summary = "Verify your BVN with the identity provider. A match on your name upgrades you to KYC tier 2 "
            + "(higher limits). Sandbox test BVN: 22222222222")
    public VerificationResult verifyBvn(@Valid @RequestBody BvnRequest request) {
        return kycService.verifyBvn(CurrentUser.require(), request.bvn());
    }

    @GetMapping
    @Operation(summary = "Your identity verification attempts, newest first")
    public List<VerificationResult> history() {
        return kycService.history(CurrentUser.require());
    }

    public record BvnRequest(@Schema(example = "22222222222") @NotBlank String bvn) {}
}

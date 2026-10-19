package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.service.AuthService;
import com.academy.paybridge.shared.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Partners are onboarded by operations after the contract is signed; they cannot self-register. */
@RestController
@RequestMapping("/api/v1/admin/partners")
@Tag(name = "9. Admin: customers")
public class AdminPartnerController {

    private final AuthService authService;

    public AdminPartnerController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Onboard a remittance partner: creates a PARTNER login and an empty USD prefund account")
    public AuthService.Registration create(@Valid @RequestBody CreatePartnerRequest request) {
        return authService.create(request.email(), request.name(), request.password(), Role.PARTNER);
    }

    public record CreatePartnerRequest(
            @Schema(example = "Diaspora Remit Inc.") @NotBlank @Size(max = 255) String name,
            @Schema(example = "api@diasporaremit.local") @NotBlank @Email String email,
            @Schema(example = "Partner-pass-2026") @NotBlank String password
    ) {}
}

package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.customer.api.CustomerStatus;
import com.academy.paybridge.customer.service.CustomerService;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/customers")
@Tag(name = "9. Admin: customers", description = "Operations-only customer controls (ROLE_ADMIN)")
public class AdminCustomerController {

    private final CustomerService customerService;

    public AdminCustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @Operation(summary = "List customers")
    public Page<CustomerDto> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return customerService.list(Math.max(page, 0), Math.max(size, 1));
    }

    @PutMapping("/{customerId}/kyc-tier")
    @Operation(summary = "Set a customer's KYC tier after verification (drives limits)")
    public CustomerDto changeKycTier(@PathVariable UUID customerId, @Valid @RequestBody KycTierRequest request) {
        return customerService.changeKycTier(customerId, request.tier(), request.reason(),
                CurrentUser.require().customerId());
    }

    @PutMapping("/{customerId}/status")
    @Operation(summary = "Suspend or reactivate a customer (suspension revokes all their sessions)")
    public CustomerDto changeStatus(@PathVariable UUID customerId, @Valid @RequestBody StatusRequest request) {
        return customerService.changeStatus(customerId, request.status(), request.reason(),
                CurrentUser.require().customerId());
    }

    public record KycTierRequest(
            @Schema(example = "2") @NotNull @Min(1) @Max(3) Integer tier,
            @Schema(example = "BVN verified, ticket KYC-881") @NotBlank @Size(max = 500) String reason
    ) {}

    public record StatusRequest(
            @Schema(example = "SUSPENDED") @NotNull CustomerStatus status,
            @Schema(example = "Fraud investigation FRD-42") @NotBlank @Size(max = 500) String reason
    ) {}
}

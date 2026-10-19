package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.api.RemittanceDto;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.remittance.service.RemittanceService;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/remittances")
@Tag(name = "10. Admin: FX and remittances", description = "Rate publishing and the compliance review queue (ROLE_ADMIN)")
public class AdminRemittanceController {

    private final RemittanceService remittanceService;

    public AdminRemittanceController(RemittanceService remittanceService) {
        this.remittanceService = remittanceService;
    }

    @GetMapping
    @Operation(summary = "List remittances. status=PENDING_REVIEW is the review queue, oldest first")
    public Page<RemittanceDto> list(@RequestParam(required = false) RemittanceStatus status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return remittanceService.listForAdmin(status, Math.max(page, 0), Math.max(size, 1));
    }

    @GetMapping("/{reference}")
    @Operation(summary = "One remittance, including screening notes")
    public RemittanceDto get(@PathVariable String reference) {
        return remittanceService.getForAdmin(reference);
    }

    @PostMapping("/{reference}/approve")
    @Operation(summary = "Clear a held remittance: the wallet is credited at the rate locked at acceptance")
    public RemittanceDto approve(@PathVariable String reference, @Valid @RequestBody ReviewRequest request) {
        return remittanceService.approve(reference, CurrentUser.require().customerId(), request.note());
    }

    @PostMapping("/{reference}/reject")
    @Operation(summary = "Reject a held remittance: the partner's USD (amount and fee) is refunded")
    public RemittanceDto reject(@PathVariable String reference, @Valid @RequestBody ReviewRequest request) {
        return remittanceService.reject(reference, CurrentUser.require().customerId(), request.note());
    }

    public record ReviewRequest(
            @Schema(example = "Name differs from watchlist entry by date of birth; false positive")
            @NotBlank @Size(max = 500) String note
    ) {}
}

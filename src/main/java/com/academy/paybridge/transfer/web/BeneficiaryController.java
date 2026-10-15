package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.CurrentUser;
import com.academy.paybridge.transfer.domain.TransferType;
import com.academy.paybridge.transfer.service.BeneficiaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/beneficiaries")
@Tag(name = "3. Beneficiaries", description = "Saved payees for faster repeat transfers")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    public BeneficiaryController(BeneficiaryService beneficiaryService) {
        this.beneficiaryService = beneficiaryService;
    }

    @GetMapping
    @Operation(summary = "List your saved beneficiaries")
    public List<BeneficiaryService.BeneficiaryDto> list() {
        return beneficiaryService.list(CurrentUser.require().customerId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a beneficiary (bank accounts are name-checked and registered with Paystack once)")
    public BeneficiaryService.BeneficiaryDto add(@Valid @RequestBody AddBeneficiaryRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        return switch (request.type()) {
            case INTERNAL -> {
                if (request.walletNumber() == null) throw PayBridgeException.badRequest("walletNumber is required");
                yield beneficiaryService.addInternal(user.customerId(), request.walletNumber(), request.nickname());
            }
            case EXTERNAL -> beneficiaryService.addExternal(user.customerId(), request.accountNumber(),
                    request.bankCode(), request.nickname());
        };
    }

    @DeleteMapping("/{beneficiaryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a saved beneficiary")
    public void delete(@PathVariable UUID beneficiaryId) {
        beneficiaryService.delete(CurrentUser.require().customerId(), beneficiaryId);
    }

    public record AddBeneficiaryRequest(
            @Schema(example = "EXTERNAL") @NotNull TransferType type,
            @Schema(example = "9087654321", description = "INTERNAL only") String walletNumber,
            @Schema(example = "0001234567", description = "EXTERNAL only") String accountNumber,
            @Schema(example = "058", description = "EXTERNAL only") String bankCode,
            @Schema(example = "Landlord") @Size(max = 100) String nickname
    ) {}
}

package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.domain.AccountStatusChange;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@Tag(name = "9. Admin: wallets", description = "Operations-only wallet controls (ROLE_ADMIN)")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @Operation(summary = "List all customer wallets")
    public Page<AccountDto> list(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return accountService.listCustomerWallets(Math.max(page, 0), Math.max(size, 1));
    }

    @PostMapping("/{walletNumber}/status")
    @Operation(summary = "Freeze, place on post-no-debit, or reactivate a wallet (audited)")
    public AccountDto changeStatus(@PathVariable String walletNumber,
                                   @Valid @RequestBody ChangeStatusRequest request) {
        return accountService.changeStatus(walletNumber, request.status(), request.reason(),
                CurrentUser.require().customerId());
    }

    @GetMapping("/{walletNumber}/status-history")
    @Operation(summary = "Audit trail of status changes for a wallet")
    public List<AccountStatusChange> statusHistory(@PathVariable String walletNumber) {
        return accountService.statusHistory(walletNumber);
    }

    public record ChangeStatusRequest(
            @Schema(example = "FROZEN")
            @NotNull AccountStatus status,
            @Schema(example = "Suspected account takeover, ticket OPS-1234")
            @NotBlank @Size(max = 500) String reason
    ) {}
}

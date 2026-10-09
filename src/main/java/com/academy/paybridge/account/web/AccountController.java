package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "1. Wallets (Account Module)", description = "View and create customer NGN wallets before and after transfers")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @Operation(summary = "List all wallets and their live balances (2 demo wallets are pre-seeded!)")
    public List<AccountDto> getAllWallets() {
        return accountService.listAllWallets();
    }

    @GetMapping("/{walletNumber}")
    @Operation(summary = "Get a specific wallet by walletNumber (e.g. WALLET-1001)")
    public AccountDto getByWalletNumber(@PathVariable String walletNumber) {
        return accountService.getByWalletNumber(walletNumber);
    }

    @PostMapping
    @Operation(summary = "Create and fund a new NGN wallet")
    public AccountDto createWallet(@Valid @RequestBody CreateWalletRequest request) {
        return accountService.createWallet(
                request.walletNumber(),
                request.ownerName(),
                request.email(),
                request.externalBankAccountNumber(),
                request.externalBankCode(),
                request.initialBalance()
        );
    }

    public record CreateWalletRequest(
            @NotBlank String walletNumber,
            @NotBlank String ownerName,
            @NotBlank String email,
            @NotBlank String externalBankAccountNumber,
            @NotBlank String externalBankCode,
            @PositiveOrZero BigDecimal initialBalance
    ) {}
}
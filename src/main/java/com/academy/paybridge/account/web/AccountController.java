package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryDto;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "1. Wallets", description = "Your wallets, statements, and wallet name enquiry")
public class AccountController {

    private final AccountService accountService;
    private final CustomerApi customerApi;
    private final LedgerApi ledgerApi;

    public AccountController(AccountService accountService, CustomerApi customerApi, LedgerApi ledgerApi) {
        this.accountService = accountService;
        this.customerApi = customerApi;
        this.ledgerApi = ledgerApi;
    }

    @GetMapping
    @Operation(summary = "List your wallets and balances")
    public List<AccountDto> myWallets() {
        return accountService.getByCustomer(CurrentUser.require().customerId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open an additional wallet (an NGN wallet is opened automatically at registration)")
    public AccountDto openWallet(@Valid @RequestBody OpenWalletRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        CustomerDto customer = customerApi.requireActive(user.customerId());
        return accountService.openWallet(customer.id(), customer.fullName(), customer.email(), request.currency());
    }

    @GetMapping("/{walletNumber}")
    @Operation(summary = "Get one of your wallets")
    public AccountDto getWallet(@PathVariable String walletNumber) {
        return accountService.getForUser(CurrentUser.require(), walletNumber);
    }

    @GetMapping("/{walletNumber}/statement")
    @Operation(summary = "Wallet statement: every debit and credit, newest first")
    public List<LedgerEntryDto> statement(@PathVariable String walletNumber,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        AccountDto wallet = accountService.getForUser(CurrentUser.require(), walletNumber);
        return ledgerApi.getEntriesForAccount(wallet.id(), Math.max(page, 0), Math.max(size, 1));
    }

    @GetMapping("/lookup")
    @Operation(summary = "Name enquiry for a PayBridge wallet before a wallet-to-wallet transfer")
    public AccountService.WalletLookup lookup(@RequestParam String walletNumber) {
        CurrentUser.require();
        return accountService.lookup(walletNumber);
    }

    public record OpenWalletRequest(
            @Schema(example = "NGN")
            @NotBlank String currency
    ) {}
}

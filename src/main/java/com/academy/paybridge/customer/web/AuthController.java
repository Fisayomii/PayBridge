package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.customer.service.AuthService;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "0. Authentication", description = "Register, log in, rotate tokens, log out")
public class AuthController {

    private final AuthService authService;
    private final CustomerApi customerApi;

    public AuthController(AuthService authService, CustomerApi customerApi) {
        this.authService = authService;
        this.customerApi = customerApi;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register as a customer. An empty NGN wallet is opened automatically (KYC tier 1).")
    public AuthService.Registration register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.fullName(), request.password());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in. Returns a 15-minute access token and a single-use refresh token.")
    public AuthService.AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access + refresh token pair (the old one stops working)")
    public AuthService.AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End the session that the refresh token belongs to")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        authService.logout(user.customerId(), request.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "Your profile, role and KYC tier")
    public CustomerDto me() {
        return customerApi.getById(CurrentUser.require().customerId());
    }

    public record RegisterRequest(
            @Schema(example = "ada@example.com")
            @NotBlank @Email @Size(max = 255) String email,

            @Schema(example = "Ada Obi")
            @NotBlank @Size(max = 255) String fullName,

            @Schema(example = "correct-horse-42", description = "At least 10 characters with letters and digits")
            @NotBlank @Size(min = 10, max = 72) String password
    ) {}

    public record LoginRequest(
            @Schema(example = "ada@example.com")
            @NotBlank @Email String email,

            @Schema(example = "correct-horse-42")
            @NotBlank String password
    ) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}
}

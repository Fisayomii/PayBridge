package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "0. Authentication (Customer Module)", description = "Register users, login to get a Bearer token, and verify current profile")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Step 1: Login with pre-seeded user (admin@paybridge.com / PayBridge@2026) to get Bearer Token")
    public AuthService.AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/register")
    @Operation(summary = "Optional: Register a new customer account for the demo")
    public Map<String, String> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.UserAccount user = authService.registerUser(request.email(), request.fullName(), request.password());
        return Map.of(
                "message", "User registered successfully. You can now login at /api/v1/auth/login",
                "email", user.email(),
                "fullName", user.fullName(),
                "role", user.role()
        );
    }

    @GetMapping("/me")
    @Operation(summary = "Verify your currently logged-in profile (requires clicking Authorize button in Swagger)")
    public AuthService.UserAccount currentProfile(
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
    ) {
        return authService.getAuthenticatedUser(authHeader);
    }

    public record LoginRequest(
            @Schema(example = "admin@paybridge.com")
            @NotBlank @Email String email,

            @Schema(example = "PayBridge@2026")
            @NotBlank String password
    ) {}

    public record RegisterRequest(
            @Schema(example = "classmate@gmail.com")
            @NotBlank @Email String email,

            @Schema(example = "Classmate Full Name")
            @NotBlank String fullName,

            @Schema(example = "SecretPass123")
            @NotBlank String password
    ) {}
}
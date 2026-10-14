package com.academy.paybridge.customer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.customer.api.CustomerStatus;
import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.domain.RefreshToken;
import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.customer.repository.RefreshTokenRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.security.Role;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LogManager.getLogger(AuthService.class);
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final CustomerRepository customerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AccountApi accountApi;
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(CustomerRepository customerRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AccountApi accountApi,
                       @Value("${paybridge.security.login.max-failed-attempts:5}") int maxFailedAttempts,
                       @Value("${paybridge.security.login.lock-duration:PT15M}") Duration lockDuration) {
        this.customerRepository = customerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.accountApi = accountApi;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public record Registration(CustomerDto customer, AccountDto wallet) {}

    public record AuthResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
                               CustomerDto customer) {}

    /** Creates a tier-1 customer and their NGN wallet in one transaction. */
    @Transactional
    public Registration register(String email, String fullName, String password) {
        return create(email, fullName, password, Role.CUSTOMER);
    }

    @Transactional
    public Registration create(String email, String fullName, String password, Role role) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (fullName == null || fullName.isBlank()) {
            throw PayBridgeException.badRequest("Full name is required");
        }
        if (customerRepository.existsByEmail(normalizedEmail)) {
            throw PayBridgeException.conflict("An account with this email already exists");
        }
        Customer customer;
        try {
            customer = customerRepository.saveAndFlush(
                    new Customer(normalizedEmail, fullName, passwordEncoder.encode(password), role));
        } catch (DataIntegrityViolationException ex) {
            throw PayBridgeException.conflict("An account with this email already exists");
        }
        AccountDto wallet = role == Role.CUSTOMER
                ? accountApi.openWallet(customer.getId(), customer.getFullName(), customer.getEmail(), "NGN")
                : null;
        return new Registration(CustomerService.toDto(customer), wallet);
    }

    /** Failed attempts are committed even though the call throws, so lockout cannot be bypassed. */
    @Transactional(noRollbackFor = PayBridgeException.class)
    public AuthResponse login(String email, String password) {
        Instant now = Instant.now();
        Customer customer = customerRepository.lockByEmail(normalizeEmail(email)).orElse(null);
        if (customer == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new PayBridgeException(INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
        }
        if (customer.isLocked(now)) {
            throw new PayBridgeException("Too many failed attempts. Try again after "
                    + customer.getLockedUntil(), HttpStatus.LOCKED);
        }
        if (!passwordEncoder.matches(password, customer.getPasswordHash())) {
            customer.recordFailedLogin(maxFailedAttempts, lockDuration, now);
            log.warn("Failed login for customer {}", customer.getId());
            throw new PayBridgeException(INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED);
        }
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new PayBridgeException("Your profile is suspended. Please contact support.", HttpStatus.FORBIDDEN);
        }
        customer.recordSuccessfulLogin();
        return respond(customer, null);
    }

    /**
     * Rotates a refresh token. Presenting an already-rotated token means it was copied by someone else:
     * the whole session family is revoked and both parties must log in again.
     */
    @Transactional(noRollbackFor = PayBridgeException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = Instant.now();
        RefreshToken token = refreshTokenRepository.lockByTokenHash(TokenService.hash(nullToEmpty(rawRefreshToken)))
                .orElseThrow(AuthService::invalidRefreshToken);
        if (token.isRevoked()) {
            refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
            log.warn("SECURITY refresh token reuse detected for customer {}; session family revoked",
                    token.getCustomerId());
            throw invalidRefreshToken();
        }
        if (token.isExpired(now)) {
            throw invalidRefreshToken();
        }
        token.revoke(now);
        Customer customer = customerRepository.findById(token.getCustomerId()).orElseThrow(AuthService::invalidRefreshToken);
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
            throw new PayBridgeException("Your profile is suspended. Please contact support.", HttpStatus.FORBIDDEN);
        }
        return respond(customer, token.getFamilyId());
    }

    /** Ends the session the refresh token belongs to. Only the token's owner can do this. */
    @Transactional
    public void logout(UUID callerId, String rawRefreshToken) {
        refreshTokenRepository.lockByTokenHash(TokenService.hash(nullToEmpty(rawRefreshToken)))
                .filter(t -> t.getCustomerId().equals(callerId))
                .ifPresent(t -> refreshTokenRepository.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    private AuthResponse respond(Customer customer, UUID familyId) {
        TokenService.IssuedTokens tokens = tokenService.issue(customer, familyId);
        return new AuthResponse(tokens.accessToken(), "Bearer", tokens.expiresInSeconds(), tokens.refreshToken(),
                CustomerService.toDto(customer));
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 10) {
            throw PayBridgeException.badRequest("Password must be at least 10 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw PayBridgeException.badRequest("Password must be at most 72 bytes"); // BCrypt input limit
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw PayBridgeException.badRequest("Password must contain both letters and digits");
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw PayBridgeException.badRequest("Email is required");
        }
        return email.trim().toLowerCase();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static PayBridgeException invalidRefreshToken() {
        return new PayBridgeException("Invalid or expired refresh token. Please log in again.", HttpStatus.UNAUTHORIZED);
    }
}

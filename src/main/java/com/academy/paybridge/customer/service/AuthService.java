package com.academy.paybridge.customer.service;

import com.academy.paybridge.shared.exception.PayBridgeException;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    public record UserAccount(String email, String fullName, String password, String role) {}
    public record AuthResponse(String accessToken, String tokenType, String email, String fullName, String role, Instant issuedAt) {}

    private final Map<String, UserAccount> usersByEmail = new ConcurrentHashMap<>();
    private final Map<String, UserAccount> activeTokens = new ConcurrentHashMap<>();

    @PostConstruct
    public void seedDefaultUsers() {
        // Pre-seeded users ready for your live demo
        usersByEmail.put("admin@paybridge.com", new UserAccount(
                "admin@paybridge.com",
                "Oluwafisayomi Aiyetogbon (PayBridge Admin)",
                "PayBridge@2026",
                "ROLE_ADMIN"
        ));
        usersByEmail.put("adebayo@paybridge.com", new UserAccount(
                "adebayo@paybridge.com",
                "Adebayo Ogunlesi",
                "Password123!",
                "ROLE_CUSTOMER"
        ));
    }

    public UserAccount registerUser(String email, String fullName, String password) {
        String key = email.toLowerCase().trim();
        if (usersByEmail.containsKey(key)) {
            throw PayBridgeException.badRequest("User with email " + email + " already exists");
        }
        UserAccount created = new UserAccount(key, fullName, password, "ROLE_CUSTOMER");
        usersByEmail.put(key, created);
        return created;
    }

    public AuthResponse login(String email, String password) {
        UserAccount user = usersByEmail.get(email.toLowerCase().trim());
        if (user == null || !user.password().equals(password)) {
            throw new PayBridgeException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }
        String token = "pb_tok_" + UUID.randomUUID().toString().replace("-", "");
        activeTokens.put(token, user);
        return new AuthResponse(token, "Bearer", user.email(), user.fullName(), user.role(), Instant.now());
    }

    public UserAccount getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new PayBridgeException("Missing or invalid Authorization Bearer header. Please login at /api/v1/auth/login and click Authorize in Swagger.", HttpStatus.UNAUTHORIZED);
        }
        String token = authHeader.substring(7).trim();
        UserAccount user = activeTokens.get(token);
        if (user == null) {
            throw new PayBridgeException("Expired or invalid token. Please login again.", HttpStatus.UNAUTHORIZED);
        }
        return user;
    }
}
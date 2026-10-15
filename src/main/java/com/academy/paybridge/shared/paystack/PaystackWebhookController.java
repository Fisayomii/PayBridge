package com.academy.paybridge.shared.paystack;

import com.academy.paybridge.shared.exception.PayBridgeException;
import io.swagger.v3.oas.annotations.Hidden;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Single entry point for all Paystack webhooks. Every request is authenticated by verifying the
 * {@code x-paystack-signature} header (HMAC-SHA512 of the raw body keyed with the secret key)
 * before anything is parsed, then dispatched to the module that owns the event.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/webhooks/paystack")
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "paystack", matchIfMissing = true)
public class PaystackWebhookController {

    private static final Logger log = LogManager.getLogger(PaystackWebhookController.class);
    static final String SIGNATURE_HEADER = "x-paystack-signature";

    private final List<PaystackWebhookHandler> handlers;
    private final ObjectMapper objectMapper;
    private final byte[] secretKey;

    public PaystackWebhookController(List<PaystackWebhookHandler> handlers,
                                     ObjectMapper objectMapper,
                                     @Value("${paybridge.paystack.secret-key}") String secretKey) {
        this.handlers = handlers;
        this.objectMapper = objectMapper;
        this.secretKey = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!isValidSignature(rawBody, signature)) {
            log.warn("Rejected Paystack webhook with missing or invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JacksonException ex) {
            log.warn("Ignoring unreadable Paystack webhook: {}", ex.getOriginalMessage());
            return ResponseEntity.ok().build();
        }
        String event = root.path("event").asString("");
        JsonNode data = root.path("data");

        for (PaystackWebhookHandler handler : handlers) {
            if (!handler.handles(event)) continue;
            try {
                handler.handle(event, data);
            } catch (PayBridgeException ex) {
                if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                    // Not ours (e.g. created on the Paystack dashboard). Acknowledge so Paystack stops retrying.
                    log.warn("Paystack webhook {} for unknown reference: {}", event, ex.getMessage());
                    return ResponseEntity.ok().build();
                }
                throw ex; // 4xx/5xx -> Paystack redelivers
            }
            return ResponseEntity.ok().build();
        }
        log.debug("Ignoring Paystack webhook event {}", event);
        return ResponseEntity.ok().build();
    }

    private boolean isValidSignature(byte[] body, String signature) {
        if (signature == null || signature.isBlank() || secretKey.length == 0) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA512"));
            byte[] expected = mac.doFinal(body);
            byte[] provided = HexFormat.of().parseHex(signature.trim().toLowerCase());
            return MessageDigest.isEqual(expected, provided);
        } catch (IllegalArgumentException ex) {
            return false; // not valid hex
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA512 unavailable", ex);
        }
    }
}

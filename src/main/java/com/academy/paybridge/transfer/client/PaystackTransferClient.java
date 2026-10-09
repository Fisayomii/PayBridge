package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.money.Money;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Component
public class PaystackTransferClient implements TransferGateway {

    private static final Logger log = LogManager.getLogger(PaystackTransferClient.class);

    private final RestClient restClient;
    private final String secretKey;

    public PaystackTransferClient(
            RestClient.Builder restClientBuilder,
            @Value("${paybridge.paystack.base-url}") String baseUrl,
            @Value("${paybridge.paystack.secret-key}") String secretKey
    ) {
        this.secretKey = secretKey;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }


    @Override
    @Retry(name = "paystackRetry", fallbackMethod = "resolveFallback")
    @CircuitBreaker(name = "paystackCircuitBreaker", fallbackMethod = "resolveFallback")
    public BankResolutionResult resolveBankAccount(String accountNumber, String bankCode) {
        log.info("Attempting to resolve account {} via Paystack...", accountNumber);

        PaystackResolveResponse response = restClient.get()
                .uri("/bank/resolve?account_number={acc}&bank_code={bank}", accountNumber, bankCode)
                .retrieve()
                .body(PaystackResolveResponse.class);

        if (response == null || !response.status()) {
            throw new RuntimeException("Paystack resolution failed");
        }
        return new BankResolutionResult(accountNumber, response.data().account_name(), bankCode, "PAYSTACK_LIVE_SANDBOX");
    }

    // The automated fallback method triggered by Resilience4j
    public BankResolutionResult resolveFallback(String accountNumber, String bankCode, Throwable throwable) {
        log.warn("Paystack call failed/circuit broken. Falling back to simulated resolution for {}. Error: {}", accountNumber, throwable.getMessage());
        return new BankResolutionResult(accountNumber, "PAYSTACK SANDBOX USER - " + accountNumber, bankCode, "PAYSTACK_SANDBOX_SIMULATION");
    }
    @Override
    @SuppressWarnings("unchecked")
    public String createTransferRecipient(String accountName, String accountNumber, String bankCode) {
        if (isLiveSandboxKeyConfigured()) {
            try {
                Map<String, Object> payload = Map.of(
                        "type", "nuban",
                        "name", accountName,
                        "account_number", accountNumber,
                        "bank_code", bankCode,
                        "currency", "NGN"
                );
                Map<String, Object> response = restClient.post()
                        .uri("/transferrecipient")
                        .body(payload)
                        .retrieve()
                        .body(Map.class);

                if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    return String.valueOf(data.get("recipient_code"));
                }
            } catch (Exception ex) {
                log.warn("Paystack /transferrecipient fell back to local sandbox simulation: {}", ex.getMessage());
            }
        }
        return "RCP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @Override
    @SuppressWarnings("unchecked")
    public GatewayPayoutResult initiateTransfer(String recipientCode, BigDecimal amountNgn, String reference, String narration) {
        long amountInKobo = Money.ngn(amountNgn).toMinorUnits();

        if (isLiveSandboxKeyConfigured()) {
            try {
                Map<String, Object> payload = Map.of(
                        "source", "balance",
                        "amount", amountInKobo,
                        "recipient", recipientCode,
                        "reference", reference,
                        "reason", narration
                );
                Map<String, Object> response = restClient.post()
                        .uri("/transfer")
                        .body(payload)
                        .retrieve()
                        .body(Map.class);

                if (response != null && Boolean.TRUE.equals(response.get("status"))) {
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    String trfCode = String.valueOf(data.getOrDefault("transfer_code", "TRF_LIVE"));
                    String status = String.valueOf(data.getOrDefault("status", "success"));
                    return new GatewayPayoutResult(trfCode, recipientCode, status.toUpperCase(), "Paystack sandbox transfer initiated");
                }
            } catch (Exception ex) {
                log.warn("Paystack /transfer fell back to local sandbox simulation: {}", ex.getMessage());
            }
        }

        String simulatedTrfCode = "TRF_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return new GatewayPayoutResult(
                simulatedTrfCode,
                recipientCode,
                "SUCCESS",
                "Simulated Paystack Sandbox transfer of " + amountInKobo + " kobo completed"
        );
    }

    private boolean isLiveSandboxKeyConfigured() {
        return secretKey != null
                && secretKey.startsWith("sk_test_")
                && !secretKey.equals("sk_test_placeholder");
    }
}
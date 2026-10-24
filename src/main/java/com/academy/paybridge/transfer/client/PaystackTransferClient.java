package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.shared.paystack.PaystackHttpClient;
import com.academy.paybridge.shared.paystack.PaystackResponse;
import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live Paystack Transfers API (test or live keys). There is deliberately no "simulated success" fallback:
 * failures surface as GatewayRejectedException or GatewayUnavailableException, never as a fake success.
 * For offline demos use {@code paybridge.payments.provider=simulated}.
 */
@Component
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "paystack", matchIfMissing = true)
public class PaystackTransferClient implements TransferGateway {

    private static final Logger log = LogManager.getLogger(PaystackTransferClient.class);

    private static final ParameterizedTypeReference<PaystackResponse<ResolveData>> RESOLVE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<PaystackResponse<RecipientData>> RECIPIENT =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<PaystackResponse<TransferData>> TRANSFER =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<PaystackResponse<List<BankData>>> BANKS =
            new ParameterizedTypeReference<>() {};
    private static final int BANK_PAGE_SIZE = 100;
    private static final int MAX_BANK_PAGES = 10;

    private final PaystackHttpClient paystack;

    public PaystackTransferClient(PaystackHttpClient paystack) {
        this.paystack = paystack;
    }

    @Override
    @Retry(name = "paystack")
    @CircuitBreaker(name = "paystack")
    public BankResolutionResult resolveBankAccount(String accountNumber, String bankCode) {
        PaystackResponse<ResolveData> response = paystack.get("resolve account", RESOLVE,
                "/bank/resolve?account_number={acc}&bank_code={bank}", accountNumber, bankCode);

        if (!response.status() || response.data() == null || isBlank(response.data().accountName())) {
            throw new GatewayRejectedException("Could not resolve account: " + response.message());
        }
        return new BankResolutionResult(accountNumber, response.data().accountName(), bankCode, "PAYSTACK");
    }

    /**
     * GET /bank?country=nigeria&currency=NGN, page by page. Stops when a page is short or adds no new banks,
     * so it is correct whether Paystack pages the list or returns it whole.
     */
    @Override
    @Retry(name = "paystack")
    @CircuitBreaker(name = "paystack")
    public List<Bank> listBanks() {
        Map<String, Bank> byCode = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_BANK_PAGES; page++) {
            PaystackResponse<List<BankData>> response = paystack.get("list banks", BANKS,
                    "/bank?country=nigeria&currency=NGN&perPage={size}&page={page}", BANK_PAGE_SIZE, page);
            if (!response.status() || response.data() == null) {
                throw new GatewayRejectedException("Could not list banks: " + response.message());
            }
            int before = byCode.size();
            for (BankData b : response.data()) {
                boolean usable = !isBlank(b.code()) && !isBlank(b.name())
                        && !Boolean.FALSE.equals(b.active()) && !Boolean.TRUE.equals(b.isDeleted());
                if (usable) {
                    byCode.putIfAbsent(b.code(), new Bank(b.code(), b.name().trim()));
                }
            }
            if (response.data().size() < BANK_PAGE_SIZE || byCode.size() == before) {
                break;
            }
        }
        List<Bank> banks = new ArrayList<>(byCode.values());
        banks.sort(Comparator.comparing(Bank::name, String.CASE_INSENSITIVE_ORDER));
        log.info("Loaded {} banks from Paystack", banks.size());
        return banks;
    }

    @Override
    @Retry(name = "paystack")
    @CircuitBreaker(name = "paystack")
    public String createTransferRecipient(String accountName, String accountNumber, String bankCode) {
        Map<String, Object> payload = Map.of(
                "type", "nuban",
                "name", accountName,
                "account_number", accountNumber,
                "bank_code", bankCode,
                "currency", "NGN"
        );
        // Paystack returns the existing recipient for identical details, so retrying is safe.
        PaystackResponse<RecipientData> response = paystack.post("create recipient", RECIPIENT, "/transferrecipient", payload);

        if (!response.status() || response.data() == null || isBlank(response.data().recipientCode())) {
            throw new GatewayRejectedException("Could not create transfer recipient: " + response.message());
        }
        return response.data().recipientCode();
    }

    /**
     * Not retried automatically: if the response is lost we cannot know whether Paystack queued the payout.
     * The reconciler verifies by reference and only re-initiates when Paystack reports NOT_FOUND.
     */
    @Override
    public PayoutResult initiateTransfer(String recipientCode, BigDecimal amount, String currency,
                                         String reference, String narration) {
        Map<String, Object> payload = Map.of(
                "source", "balance",
                "amount", Money.of(amount, currency).toMinorUnits(),
                "currency", currency,
                "recipient", recipientCode,
                "reference", reference,
                "reason", narration
        );
        PaystackResponse<TransferData> response = paystack.post("initiate transfer", TRANSFER, "/transfer", payload);

        if (!response.status() || response.data() == null) {
            throw new GatewayRejectedException("Paystack rejected transfer: " + response.message());
        }
        return toPayoutResult(response.data(), response.message());
    }

    @Override
    @Retry(name = "paystack")
    @CircuitBreaker(name = "paystack")
    public PayoutResult verifyTransfer(String reference) {
        PaystackResponse<TransferData> response;
        try {
            response = paystack.get("verify transfer", TRANSFER, "/transfer/verify/{reference}", reference);
        } catch (GatewayRejectedException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("not found")) {
                return new PayoutResult(PayoutStatus.NOT_FOUND, null, ex.getMessage());
            }
            throw ex;
        }
        if (!response.status() || response.data() == null) {
            return new PayoutResult(PayoutStatus.NOT_FOUND, null, response.message());
        }
        return toPayoutResult(response.data(), response.message());
    }

    private PayoutResult toPayoutResult(TransferData data, String message) {
        String status = data.status() == null ? "" : data.status().toLowerCase();
        PayoutStatus mapped = switch (status) {
            case "success" -> PayoutStatus.SUCCESS;
            case "failed", "abandoned", "rejected", "blocked" -> PayoutStatus.FAILED;
            case "reversed" -> PayoutStatus.REVERSED;
            case "otp" -> {
                log.warn("Paystack transfer {} awaits OTP. Disable transfer OTP on the Paystack dashboard for API payouts.",
                        data.reference());
                yield PayoutStatus.PENDING;
            }
            default -> PayoutStatus.PENDING; // pending, queued, received, processing
        };
        String detail = data.reason() != null ? data.reason() : message;
        return new PayoutResult(mapped, data.transferCode(), detail);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveData(
            @JsonProperty("account_number") String accountNumber,
            @JsonProperty("account_name") String accountName
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BankData(
            String name,
            String code,
            Boolean active,
            @JsonProperty("is_deleted") Boolean isDeleted
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecipientData(
            @JsonProperty("recipient_code") String recipientCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferData(
            @JsonProperty("transfer_code") String transferCode,
            String reference,
            String status,
            String reason
    ) {}
}

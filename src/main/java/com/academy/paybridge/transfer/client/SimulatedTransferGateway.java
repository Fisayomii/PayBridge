package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.academy.paybridge.shared.provider.GatewayUnavailableException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline stand-in for Paystack, enabled only with {@code paybridge.payments.provider=simulated}.
 * Behaves like a real provider: payouts are accepted as PENDING and settle later, either through
 * the simulation webhook endpoint or when the reconciler verifies them.
 *
 * <p>Magic account numbers for demoing unhappy paths:
 * <ul>
 *   <li>{@code 9999999999} - name enquiry fails (account does not exist)</li>
 *   <li>{@code 8888888888} - provider rejects the payout immediately (customer is refunded)</li>
 *   <li>{@code 7777777777} - provider times out on initiation (payout stays PENDING until reconciled)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "paybridge.payments.provider", havingValue = "simulated")
public class SimulatedTransferGateway implements TransferGateway {

    private static final Logger log = LogManager.getLogger(SimulatedTransferGateway.class);

    static final String UNRESOLVABLE_ACCOUNT = "9999999999";
    static final String REJECTED_ACCOUNT = "8888888888";
    static final String TIMEOUT_ACCOUNT = "7777777777";

    private final Map<String, String> recipientAccounts = new ConcurrentHashMap<>();
    private final Map<String, PayoutResult> payouts = new ConcurrentHashMap<>();

    @Override
    public BankResolutionResult resolveBankAccount(String accountNumber, String bankCode) {
        if (UNRESOLVABLE_ACCOUNT.equals(accountNumber)) {
            throw new GatewayRejectedException("Could not resolve account name. Check parameters or try again.");
        }
        String name = "SIMULATED BENEFICIARY " + accountNumber.substring(Math.max(0, accountNumber.length() - 4));
        return new BankResolutionResult(accountNumber, name, bankCode, "SIMULATED");
    }

    @Override
    public String createTransferRecipient(String accountName, String accountNumber, String bankCode) {
        String code = "RCP_SIM_" + accountNumber + "_" + bankCode;
        recipientAccounts.put(code, accountNumber);
        return code;
    }

    @Override
    public PayoutResult initiateTransfer(String recipientCode, BigDecimal amount, String currency,
                                         String reference, String narration) {
        PayoutResult existing = payouts.get(reference);
        if (existing != null) {
            return existing; // same de-duplication-by-reference behaviour as Paystack
        }
        String accountNumber = recipientAccounts.getOrDefault(recipientCode, "");
        if (REJECTED_ACCOUNT.equals(accountNumber)) {
            throw new GatewayRejectedException("Simulated provider rejection: beneficiary bank unavailable");
        }
        if (TIMEOUT_ACCOUNT.equals(accountNumber)) {
            throw new GatewayUnavailableException("Simulated provider timeout", null);
        }
        PayoutResult accepted = new PayoutResult(PayoutStatus.PENDING, newTransferCode(), "Transfer has been queued");
        payouts.put(reference, accepted);
        log.info("[SIMULATED GATEWAY] Queued payout {} of {} {}", reference, currency, amount);
        return accepted;
    }

    @Override
    public PayoutResult verifyTransfer(String reference) {
        PayoutResult current = payouts.get(reference);
        if (current == null) {
            return new PayoutResult(PayoutStatus.NOT_FOUND, null, "Transfer not found");
        }
        if (current.status() == PayoutStatus.PENDING) {
            // Pretend the beneficiary bank has confirmed by the time anyone asks.
            current = new PayoutResult(PayoutStatus.SUCCESS, current.gatewayReference(), "Transfer successful");
            payouts.put(reference, current);
        }
        return current;
    }

    /** Lets the simulation webhook endpoint drive a payout to its final state. */
    public PayoutResult settle(String reference, PayoutStatus status, String reason) {
        PayoutResult current = payouts.get(reference);
        String code = current != null ? current.gatewayReference() : newTransferCode();
        PayoutResult settled = new PayoutResult(status, code, reason);
        payouts.put(reference, settled);
        return settled;
    }

    private static String newTransferCode() {
        return "TRF_SIM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}

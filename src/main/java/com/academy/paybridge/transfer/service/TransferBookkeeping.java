package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.account.api.MovementKind;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Every money movement in the transfer module goes through {@link #post}: the balance change and its
 * double-entry ledger lines are written together, inside the caller's transaction, or not at all.
 *
 * <p>Postings (reference suffix in brackets):
 * <pre>
 *   internal       sender wallet        -> receiver wallet       [ref]
 *   fee            sender wallet        -> fee income            [ref-FEE]
 *   payout hold    sender wallet        -> payout clearing       [ref]
 *   payout settle  payout clearing      -> provider settlement   [ref-SETTLE]
 *   payout refund  payout clearing      -> sender wallet         [ref-REV]   (failed before settlement)
 *   payout reverse provider settlement  -> sender wallet         [ref-REV]   (provider returned funds)
 *   fee refund     fee income           -> sender wallet         [ref-FEE-REV]
 * </pre>
 * The clearing account nets to zero once every payout is final, which is the first reconciliation check
 * the settlement module should run.
 */
@Component
class TransferBookkeeping {

    static final String FEE_SUFFIX = "-FEE";
    static final String SETTLE_SUFFIX = "-SETTLE";
    static final String REVERSAL_SUFFIX = "-REV";
    static final String FEE_REVERSAL_SUFFIX = "-FEE-REV";

    private final AccountApi accountApi;
    private final LedgerApi ledgerApi;

    TransferBookkeeping(AccountApi accountApi, LedgerApi ledgerApi) {
        this.accountApi = accountApi;
        this.ledgerApi = ledgerApi;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(String entryReference, UUID debitAccountId, UUID creditAccountId,
                     BigDecimal amount, String currency, String narration, MovementKind kind) {
        if (amount.signum() == 0) {
            return; // e.g. a zero fee
        }
        accountApi.moveFunds(debitAccountId, creditAccountId, amount, currency, kind);
        ledgerApi.recordDoubleEntry(entryReference, debitAccountId, creditAccountId, amount, currency, narration);
    }

    /** See {@link AccountApi#lockForUpdate}: lock every account of a multi-posting transaction up front. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAll(List<UUID> accountIds) {
        accountApi.lockForUpdate(accountIds);
    }

    UUID payoutClearingAccount(String currency) {
        return gl(InternalAccounts.NGN_PAYOUT_CLEARING, currency);
    }

    UUID providerSettlementAccount(String currency) {
        return gl(InternalAccounts.NGN_PAYSTACK_SETTLEMENT, currency);
    }

    UUID feeIncomeAccount(String currency) {
        return gl(InternalAccounts.NGN_FEE_INCOME, currency);
    }

    static List<String> allEntryReferences(String reference) {
        return List.of(reference, reference + FEE_SUFFIX, reference + SETTLE_SUFFIX,
                reference + REVERSAL_SUFFIX, reference + FEE_REVERSAL_SUFFIX);
    }

    private UUID gl(String walletNumber, String currency) {
        if (!"NGN".equalsIgnoreCase(currency)) {
            throw PayBridgeException.unprocessable("Only NGN transfers are supported");
        }
        return accountApi.getByWalletNumber(walletNumber).id();
    }
}

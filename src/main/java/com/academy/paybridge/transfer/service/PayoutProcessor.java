package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.MovementKind;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutResult;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutStatus;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.domain.TransferType;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Owns the lifecycle of an EXTERNAL payout after its funds are held. All three sources of truth
 * (the initiation response, Paystack webhooks, and reconciliation polling) funnel into
 * {@link #applyGatewayResult}, which locks the transfer row and only performs legal transitions,
 * so duplicate or out-of-order notifications can never post money twice.
 */
@Service
public class PayoutProcessor {

    private static final Logger log = LogManager.getLogger(PayoutProcessor.class);

    private final TransferRepository transferRepository;
    private final TransferGateway transferGateway;
    private final TransferBookkeeping bookkeeping;
    private final TransferEventPublisher events;
    private final TransactionTemplate tx;

    public PayoutProcessor(TransferRepository transferRepository,
                           TransferGateway transferGateway,
                           TransferBookkeeping bookkeeping,
                           TransferEventPublisher events,
                           TransactionTemplate tx) {
        this.transferRepository = transferRepository;
        this.transferGateway = transferGateway;
        this.bookkeeping = bookkeeping;
        this.events = events;
        this.tx = tx;
    }

    /**
     * Sends a held payout to the provider. Must be called outside any transaction: no row locks are
     * held during the HTTP call. If the outcome is unknown the transfer stays PENDING (funds remain
     * held) and the reconciler resolves it.
     */
    void dispatch(Transfer transfer) {
        String reference = transfer.getReference();
        try {
            PayoutResult result = transferGateway.initiateTransfer(transfer.getRecipientCode(), transfer.getAmount(),
                    transfer.getCurrency(), reference, transfer.getNarration());
            applyGatewayResult(reference, result.status(), result.gatewayReference(), result.message());
        } catch (GatewayRejectedException ex) {
            applyGatewayResult(reference, PayoutStatus.FAILED, null, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Payout {} outcome unknown ({}); funds stay held until reconciliation", reference, ex.getMessage());
        }
    }

    /** Idempotent: re-applying the same (or a stale) status is a no-op. */
    public Transfer applyGatewayResult(String reference, PayoutStatus status, String gatewayCode, String message) {
        return tx.execute(txStatus -> {
            Transfer t = transferRepository.lockByReference(reference)
                    .orElseThrow(() -> PayBridgeException.notFound("Transfer not found: " + reference));
            if (t.getType() != TransferType.EXTERNAL) {
                throw PayBridgeException.badRequest("Transfer " + reference + " is not an external payout");
            }
            TransferStatus before = t.getStatus();
            switch (status) {
                case PENDING -> {
                    if (before == TransferStatus.PENDING) t.markProcessing(gatewayCode);
                }
                case SUCCESS -> {
                    if (!before.isFinal()) {
                        bookkeeping.post(reference + TransferBookkeeping.SETTLE_SUFFIX,
                                bookkeeping.payoutClearingAccount(t.getCurrency()),
                                bookkeeping.providerSettlementAccount(t.getCurrency()),
                                t.getAmount(), t.getCurrency(), "Payout settled: " + t.getNarration(),
                                MovementKind.SYSTEM);
                        t.markSucceeded(gatewayCode);
                    } else if (before != TransferStatus.SUCCESS) {
                        log.error("RECONCILIATION ALERT: provider reports {} succeeded but it is {} locally "
                                + "(customer already refunded). Manual review required.", reference, before);
                    }
                }
                case FAILED -> {
                    if (!before.isFinal()) {
                        refund(t, bookkeeping.payoutClearingAccount(t.getCurrency()), "Payout failed, refund: ");
                        t.markFailed(message);
                    } else if (before == TransferStatus.SUCCESS) {
                        log.error("RECONCILIATION ALERT: provider reports {} failed after success. "
                                + "Waiting for transfer.reversed before refunding.", reference);
                    }
                }
                case REVERSED -> {
                    if (!before.isFinal()) {
                        refund(t, bookkeeping.payoutClearingAccount(t.getCurrency()), "Payout reversed, refund: ");
                        t.markReversed(message);
                    } else if (before == TransferStatus.SUCCESS) {
                        refund(t, bookkeeping.providerSettlementAccount(t.getCurrency()), "Payout reversed by bank: ");
                        t.markReversed(message);
                    }
                }
                case NOT_FOUND -> { /* handled by the reconciler */ }
            }
            if (t.getStatus() != before) {
                log.info("Payout {} moved {} -> {}", reference, before, t.getStatus());
                if (t.getStatus().isFinal()) {
                    events.publish(t);
                }
            }
            return t;
        });
    }

    /** Returns principal (from wherever it currently sits) and the fee to the sender, regardless of freezes. */
    private void refund(Transfer t, UUID principalHeldIn, String narrationPrefix) {
        UUID feeIncome = bookkeeping.feeIncomeAccount(t.getCurrency());
        bookkeeping.lockAll(List.of(principalHeldIn, t.getSourceAccountId(), feeIncome));
        bookkeeping.post(t.getReference() + TransferBookkeeping.REVERSAL_SUFFIX, principalHeldIn, t.getSourceAccountId(),
                t.getAmount(), t.getCurrency(), narrationPrefix + t.getNarration(), MovementKind.SYSTEM);
        bookkeeping.post(t.getReference() + TransferBookkeeping.FEE_REVERSAL_SUFFIX,
                feeIncome, t.getSourceAccountId(),
                t.getFee(), t.getCurrency(), "Fee refund: " + t.getNarration(), MovementKind.SYSTEM);
    }

    /**
     * Resolves payouts stuck in PENDING/PROCESSING (lost webhook, timeout on initiation, crash between
     * steps). Safe to run concurrently on several instances: every update goes through the row lock,
     * and re-initiation reuses the original reference, which the provider de-duplicates.
     */
    public int reconcileStalePayouts(Duration minAge, int batchSize) {
        List<Transfer> stale = transferRepository.findByTypeAndStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
                TransferType.EXTERNAL,
                EnumSet.of(TransferStatus.PENDING, TransferStatus.PROCESSING),
                Instant.now().minus(minAge),
                PageRequest.of(0, batchSize));

        for (Transfer t : stale) {
            try {
                PayoutResult result = transferGateway.verifyTransfer(t.getReference());
                if (result.status() == PayoutStatus.NOT_FOUND) {
                    if (t.getStatus() == TransferStatus.PENDING) {
                        log.info("Payout {} never reached the provider; re-initiating", t.getReference());
                        dispatch(t);
                    } else {
                        log.error("RECONCILIATION ALERT: payout {} is PROCESSING locally but unknown to the provider",
                                t.getReference());
                    }
                } else {
                    applyGatewayResult(t.getReference(), result.status(), result.gatewayReference(), result.message());
                }
            } catch (RuntimeException ex) {
                log.warn("Could not reconcile payout {}: {}", t.getReference(), ex.getMessage());
            }
        }
        return stale.size();
    }
}

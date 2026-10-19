package com.academy.paybridge.transfer.service;

import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.api.PayoutReconciliationApi;
import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutResult;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutStatus;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.domain.TransferType;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Service
public class PayoutReconciliationService implements PayoutReconciliationApi {

    private static final Logger log = LogManager.getLogger(PayoutReconciliationService.class);

    private final TransferRepository transferRepository;
    private final TransferGateway transferGateway;
    private final PayoutProcessor payoutProcessor;
    private final TransferService transferService;

    public PayoutReconciliationService(TransferRepository transferRepository, TransferGateway transferGateway,
                                       PayoutProcessor payoutProcessor, TransferService transferService) {
        this.transferRepository = transferRepository;
        this.transferGateway = transferGateway;
        this.payoutProcessor = payoutProcessor;
        this.transferService = transferService;
    }

    /** No transaction: one provider call per payout, and no row locks are held while waiting on HTTP. */
    @Override
    public List<PayoutCheck> checkPayoutsWithProvider(Instant from, Instant to) {
        List<Transfer> payouts = transferRepository.findByTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                TransferType.EXTERNAL, from, to);
        List<PayoutCheck> checks = new ArrayList<>(payouts.size());
        for (Transfer t : payouts) {
            String providerStatus;
            String message;
            try {
                PayoutResult result = transferGateway.verifyTransfer(t.getReference());
                providerStatus = result.status().name();
                message = result.message();
            } catch (RuntimeException ex) {
                log.warn("Could not verify payout {} during reconciliation: {}", t.getReference(), ex.getMessage());
                providerStatus = "UNAVAILABLE";
                message = ex.getMessage();
            }
            checks.add(new PayoutCheck(t.getReference(), t.getAmount(), t.getCurrency(), t.getStatus().name(),
                    providerStatus, message));
        }
        return checks;
    }

    @Override
    public TransferDto acceptProviderStatus(String reference) {
        PayoutResult result = transferGateway.verifyTransfer(reference);
        if (result.status() == PayoutStatus.NOT_FOUND) {
            throw PayBridgeException.unprocessable("The provider has no record of payout " + reference
                    + "; resolve it manually");
        }
        payoutProcessor.applyGatewayResult(reference, result.status(), result.gatewayReference(), result.message());
        return transferService.getByReference(reference);
    }

    @Override
    public BigDecimal openPayoutTotal(String currency) {
        return transferRepository.sumOpenPayouts(currency,
                EnumSet.of(TransferStatus.PENDING, TransferStatus.PROCESSING));
    }
}

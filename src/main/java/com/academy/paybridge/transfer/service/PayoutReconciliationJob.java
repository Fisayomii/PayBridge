package com.academy.paybridge.transfer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Webhooks get lost and requests time out; this job is the safety net that guarantees every
 * held payout eventually reaches a final state.
 */
@Component
@ConditionalOnProperty(name = "paybridge.transfer.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class PayoutReconciliationJob {

    private static final Logger log = LogManager.getLogger(PayoutReconciliationJob.class);

    private final PayoutProcessor payoutProcessor;
    private final Duration minAge;
    private final int batchSize;

    public PayoutReconciliationJob(PayoutProcessor payoutProcessor,
                                   @Value("${paybridge.transfer.reconciliation.min-age:PT2M}") Duration minAge,
                                   @Value("${paybridge.transfer.reconciliation.batch-size:100}") int batchSize) {
        this.payoutProcessor = payoutProcessor;
        this.minAge = minAge;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${paybridge.transfer.reconciliation.interval:PT1M}",
               initialDelayString = "${paybridge.transfer.reconciliation.initial-delay:PT30S}")
    public void run() {
        int checked = payoutProcessor.reconcileStalePayouts(minAge, batchSize);
        if (checked > 0) {
            log.info("Payout reconciliation checked {} stale payout(s)", checked);
        }
    }
}

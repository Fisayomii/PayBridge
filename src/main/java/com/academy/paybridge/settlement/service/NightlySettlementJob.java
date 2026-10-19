package com.academy.paybridge.settlement.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * End of day, Lagos time: reconcile yesterday's bank payouts with the provider, then run the integrity checks.
 * Runs on every instance; with more than one instance, add ShedLock (see README trade-offs).
 */
@Component
@ConditionalOnProperty(name = "paybridge.settlement.nightly.enabled", havingValue = "true", matchIfMissing = true)
public class NightlySettlementJob {

    private static final Logger log = LogManager.getLogger(NightlySettlementJob.class);

    private final ReconciliationService reconciliationService;

    public NightlySettlementJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${paybridge.settlement.nightly.cron:0 30 0 * * *}", zone = "Africa/Lagos")
    public void run() {
        LocalDate yesterday = LocalDate.now(ReconciliationService.LAGOS).minusDays(1);
        try {
            var result = reconciliationService.reconcilePayouts(yesterday, null);
            log.info("Nightly payout reconciliation for {}: {} checked, {} matched, {} new breaks",
                    yesterday, result.checked(), result.matched(), result.newBreaks());
            var report = reconciliationService.integrityCheck();
            if (report.healthy()) {
                log.info("Nightly integrity check passed ({} checks)", report.checks().size());
            } else {
                log.error("INTEGRITY ALERT: {}", report.checks().stream().filter(c -> !c.passed()).toList());
            }
        } catch (RuntimeException ex) {
            log.error("Nightly settlement job failed", ex);
        }
    }
}

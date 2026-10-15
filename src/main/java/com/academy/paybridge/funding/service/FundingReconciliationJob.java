package com.academy.paybridge.funding.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Catches top-ups whose charge.success webhook never arrived, and abandons checkouts nobody paid. */
@Component
@ConditionalOnProperty(name = "paybridge.funding.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class FundingReconciliationJob {

    private static final Logger log = LogManager.getLogger(FundingReconciliationJob.class);

    private final FundingService fundingService;
    private final Duration minAge;
    private final Duration abandonAfter;

    public FundingReconciliationJob(FundingService fundingService,
                                    @Value("${paybridge.funding.reconciliation.min-age:PT5M}") Duration minAge,
                                    @Value("${paybridge.funding.reconciliation.abandon-after:PT24H}") Duration abandonAfter) {
        this.fundingService = fundingService;
        this.minAge = minAge;
        this.abandonAfter = abandonAfter;
    }

    @Scheduled(fixedDelayString = "${paybridge.funding.reconciliation.interval:PT5M}",
               initialDelayString = "${paybridge.funding.reconciliation.initial-delay:PT1M}")
    public void run() {
        int checked = fundingService.reconcileStaleCheckouts(minAge, abandonAfter, 100);
        if (checked > 0) {
            log.info("Funding reconciliation checked {} pending checkout(s)", checked);
        }
    }
}

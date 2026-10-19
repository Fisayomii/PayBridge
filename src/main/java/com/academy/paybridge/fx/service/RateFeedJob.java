package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.client.RateFeed;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the live rate feed on startup and then every {@code paybridge.fx.feed.refresh}. */
@Component
@ConditionalOnProperty(name = "paybridge.fx.feed.enabled", havingValue = "true")
public class RateFeedJob {

    private static final Logger log = LogManager.getLogger(RateFeedJob.class);

    private final RateFeed feed;
    private final FxService fxService;

    public RateFeedJob(RateFeed feed, FxService fxService) {
        this.feed = feed;
        this.fxService = fxService;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${paybridge.fx.feed.refresh:PT30M}")
    public void refresh() {
        try {
            fxService.publishFromFeed(feed.fetchUsdNgn(), feed.name());
        } catch (RuntimeException ex) {
            // Keep the last good rate; quoting pauses by itself once it is older than max-age.
            log.warn("Rate feed {} unavailable: {}", feed.name(), ex.getMessage());
        }
    }
}

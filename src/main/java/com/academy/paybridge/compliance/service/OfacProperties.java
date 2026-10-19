package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The US Treasury OFAC Specially Designated Nationals list, downloaded from OFAC's public Sanctions List Service.
 *
 * @param enabled  screen remittances against it. When enabled but not loaded, screening fails closed (REVIEW).
 * @param sdnUrl   primary names (SDN.CSV)
 * @param altUrl   aliases (ALT.CSV)
 * @param refresh  how often to download a fresh copy
 * @param cacheDir where the last good download is kept, so a restart does not depend on OFAC being reachable
 */
@ConfigurationProperties(prefix = "paybridge.compliance.ofac")
public record OfacProperties(boolean enabled, String sdnUrl, String altUrl, Duration refresh, String cacheDir) {

    public static OfacProperties disabled() {
        return new OfacProperties(false, null, null, Duration.ofDays(1), null);
    }
}

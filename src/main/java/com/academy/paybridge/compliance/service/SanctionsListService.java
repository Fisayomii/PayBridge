package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.client.OfacListClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Keeps the OFAC list fresh: the cached copy is loaded at startup (no network needed), then a fresh copy is
 * downloaded on startup and every {@code refresh}. A failed download keeps the previous list.
 */
@Service
@EnableConfigurationProperties(OfacProperties.class)
public class SanctionsListService {

    private static final Logger log = LogManager.getLogger(SanctionsListService.class);

    private final OfacProperties props;
    private final OfacListClient client;
    private final OfacSanctionsList list = new OfacSanctionsList();

    public SanctionsListService(OfacProperties props, OfacListClient client) {
        this.props = props;
        this.client = client;
        if (props.enabled() && props.cacheDir() != null) {
            list.loadFromCache(Path.of(props.cacheDir()));
        }
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${paybridge.compliance.ofac.refresh:P1D}")
    public void scheduledRefresh() {
        if (props.enabled()) {
            refresh();
        }
    }

    /** Downloads, validates by parsing, then swaps the index and the cache. */
    public OfacSanctionsList.Status refresh() {
        try {
            OfacListClient.Files files = client.download(props);
            list.load(files.sdnCsv(), files.altCsv(), "download " + props.sdnUrl());
            writeCache(files);
        } catch (RuntimeException ex) {
            list.recordError("Download failed: " + ex.getMessage());
            log.error("OFAC list refresh failed; keeping the previous list ({}): {}",
                    list.isLoaded() ? "loaded" : "NONE, screening fails closed", ex.getMessage());
        }
        return list.status();
    }

    public boolean enabled() {
        return props.enabled();
    }

    public boolean isLoaded() {
        return list.isLoaded();
    }

    public List<OfacSanctionsList.Hit> screen(String name) {
        return list.screen(name);
    }

    public OfacSanctionsList.Status status() {
        return list.status();
    }

    private void writeCache(OfacListClient.Files files) {
        if (props.cacheDir() == null) return;
        try {
            Path dir = Files.createDirectories(Path.of(props.cacheDir()));
            replace(dir.resolve("SDN.CSV"), files.sdnCsv());
            replace(dir.resolve("ALT.CSV"), files.altCsv());
        } catch (IOException ex) {
            log.warn("Could not cache the OFAC list in {}: {}", props.cacheDir(), ex.getMessage());
        }
    }

    private static void replace(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}

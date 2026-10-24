package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferGateway.Bank;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The bank list shown in "send to a bank account". Comes from the payout provider (Paystack's GET /bank),
 * cached for a day because it changes rarely and every page load would otherwise call Paystack.
 * If the provider is down, the last good list is served; if there has never been one, the built-in list.
 */
@Service
public class BankDirectory {

    private static final Logger log = LogManager.getLogger(BankDirectory.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final TransferGateway transferGateway;
    private volatile List<Bank> cached;
    private volatile Instant loadedAt = Instant.EPOCH;

    public BankDirectory(TransferGateway transferGateway) {
        this.transferGateway = transferGateway;
    }

    public List<Bank> banks() {
        List<Bank> current = cached;
        if (current != null && loadedAt.plus(TTL).isAfter(Instant.now())) {
            return current;
        }
        synchronized (this) {
            if (cached != null && loadedAt.plus(TTL).isAfter(Instant.now())) {
                return cached;
            }
            try {
                List<Bank> fresh = transferGateway.listBanks();
                if (!fresh.isEmpty()) {
                    cached = List.copyOf(fresh);
                    loadedAt = Instant.now();
                }
            } catch (RuntimeException ex) {
                log.warn("Could not load the bank list from the provider, serving the {} list: {}",
                        cached != null ? "last good" : "built-in", ex.getMessage());
            }
            return cached != null ? cached : TransferGateway.FALLBACK_BANKS;
        }
    }
}

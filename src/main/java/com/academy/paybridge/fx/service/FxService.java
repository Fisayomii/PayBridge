package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteDto;
import com.academy.paybridge.fx.client.RateFeed.FeedRate;
import com.academy.paybridge.fx.domain.FxQuote;
import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.repository.FxQuoteRepository;
import com.academy.paybridge.fx.repository.FxRateRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Prices USD -> NGN from the newest mid rate in fx_rates. Rates get there from the live feed
 * ({@link RateFeedJob}) or from an admin; both pass the same deviation guard, so neither a bad tick nor a
 * typo can become a live price.
 */
@Service
@EnableConfigurationProperties(FxProperties.class)
public class FxService implements FxApi {

    private static final Logger log = LogManager.getLogger(FxService.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    public static final String MANUAL = "MANUAL";

    private final FxQuoteRepository quoteRepository;
    private final FxRateRepository rateRepository;
    private final FxProperties props;

    public FxService(FxQuoteRepository quoteRepository, FxRateRepository rateRepository, FxProperties props) {
        this.quoteRepository = quoteRepository;
        this.rateRepository = rateRepository;
        this.props = props;
    }

    @Override
    @Transactional
    public QuoteDto createQuote(UUID partnerId, BigDecimal sourceAmount) {
        BigDecimal amount = sourceAmount.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal mid = quotableMidRate();
        BigDecimal rate = customerRate(mid);
        // Rounded down: the recipient never receives a fraction of a kobo more than the rate pays for.
        BigDecimal target = amount.multiply(rate).setScale(2, RoundingMode.DOWN);
        String reference = "pb-qte-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        FxQuote quote = quoteRepository.save(new FxQuote(reference, partnerId, amount,
                props.remittanceFee().setScale(2, RoundingMode.HALF_EVEN), mid, rate, target,
                Instant.now().plus(props.quoteTtl())));
        return toDto(quote);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public QuoteDto consumeQuote(String quoteReference, UUID partnerId) {
        FxQuote quote = quoteRepository.lockByReference(quoteReference)
                .filter(q -> q.getPartnerId().equals(partnerId))
                .orElseThrow(() -> PayBridgeException.notFound("Quote not found: " + quoteReference));
        Instant now = Instant.now();
        if (quote.getUsedAt() != null) {
            throw PayBridgeException.conflict("QUOTE_USED: Quote " + quoteReference + " was already used");
        }
        if (quote.isExpired(now)) {
            throw PayBridgeException.unprocessable("QUOTE_EXPIRED: Quote " + quoteReference
                    + " expired at " + quote.getExpiresAt() + ". Request a new quote.");
        }
        quote.markUsed(now);
        return toDto(quote);
    }

    /** Latest stored mid rate, even if stale (used for marking the FX position, never for pricing). */
    @Override
    public BigDecimal currentMidRate() {
        return latest().map(FxRate::getMidRate).orElse(props.initialMidRate());
    }

    @Transactional(readOnly = true)
    public QuoteDto getQuote(String reference, UUID partnerId) {
        return quoteRepository.findByReference(reference)
                .filter(q -> q.getPartnerId().equals(partnerId))
                .map(this::toDto)
                .orElseThrow(() -> PayBridgeException.notFound("Quote not found: " + reference));
    }

    /** What a partner sees before quoting. */
    public RateCard rateCard() {
        Optional<FxRate> latest = latest();
        BigDecimal mid = latest.map(FxRate::getMidRate).orElse(props.initialMidRate());
        return new RateCard(FxRate.USD_NGN, mid, customerRate(mid), props.spreadPercent(), props.remittanceFee(),
                props.quoteTtl().toSeconds(), latest.map(FxRate::getSource).orElse("CONFIG_DEFAULT"),
                latest.map(FxRate::getCreatedAt).orElse(null), isStale(latest));
    }

    /** An admin override. Moves over the guard need force=true. With the feed on, the next poll replaces it. */
    @Transactional
    public RateCard publishMidRate(BigDecimal newMid, UUID adminId, boolean force) {
        if (newMid == null || newMid.signum() <= 0) {
            throw PayBridgeException.badRequest("Mid rate must be positive");
        }
        Optional<String> refusal = deviationRefusal(newMid);
        if (refusal.isPresent() && !force) {
            throw new PayBridgeException(refusal.get() + " Resend with force=true if this is correct.", HttpStatus.CONFLICT);
        }
        store(newMid, MANUAL, adminId);
        log.info("AUDIT USD/NGN mid rate set to {} by {}{}", newMid, adminId, force ? " (forced)" : "");
        return rateCard();
    }

    /**
     * Stores a rate from the live feed unless it fails the deviation guard, in which case the previous rate
     * stays (and eventually goes stale, which stops quoting) until a human looks. The very first rate is
     * accepted as-is: there is nothing to compare it with.
     */
    @Transactional
    public boolean publishFromFeed(FeedRate feedRate, String source) {
        Optional<String> refusal = latest().isEmpty() ? Optional.empty() : deviationRefusal(feedRate.midRate());
        if (refusal.isPresent()) {
            log.error("RATE ALERT feed {} refused: {}", source, refusal.get());
            return false;
        }
        store(feedRate.midRate(), source, null);
        log.info("USD/NGN mid rate {} from {} (provider updated {})", feedRate.midRate(), source,
                feedRate.providerUpdatedAt());
        return true;
    }

    @Transactional(readOnly = true)
    public List<FxRate> rateHistory(int limit) {
        return rateRepository.findByPairOrderByCreatedAtDesc(FxRate.USD_NGN, PageRequest.of(0, Math.min(limit, 200)));
    }

    // ------------------------------------------------------------------ internals

    /** Refuses to price on a stale rate: a quote is a promise, and a promise on old data is a loss waiting to happen. */
    private BigDecimal quotableMidRate() {
        Optional<FxRate> latest = latest();
        if (isStale(latest)) {
            throw PayBridgeException.serviceUnavailable("RATE_UNAVAILABLE: No fresh USD/NGN rate (last "
                    + latest.map(r -> r.getCreatedAt().toString()).orElse("never") + "). Quoting is paused.");
        }
        return latest.map(FxRate::getMidRate).orElse(props.initialMidRate());
    }

    private boolean isStale(Optional<FxRate> latest) {
        if (!props.feedEnabled()) {
            return false;
        }
        return latest.isEmpty() || latest.get().getCreatedAt().isBefore(Instant.now().minus(props.feed().maxAge()));
    }

    private Optional<String> deviationRefusal(BigDecimal newMid) {
        BigDecimal current = currentMidRate();
        BigDecimal movePercent = newMid.subtract(current).abs()
                .multiply(HUNDRED).divide(current, 4, RoundingMode.HALF_EVEN);
        if (movePercent.compareTo(props.maxRateMovePercent()) <= 0) {
            return Optional.empty();
        }
        return Optional.of("RATE_DEVIATION: " + newMid.toPlainString() + " is " + movePercent.toPlainString()
                + "% away from the current " + current.toPlainString() + " (limit "
                + props.maxRateMovePercent().toPlainString() + "%).");
    }

    private void store(BigDecimal mid, String source, UUID setBy) {
        rateRepository.save(new FxRate(FxRate.USD_NGN, mid.setScale(8, RoundingMode.HALF_EVEN), source, setBy));
    }

    private Optional<FxRate> latest() {
        return rateRepository.findFirstByPairOrderByCreatedAtDesc(FxRate.USD_NGN);
    }

    private BigDecimal customerRate(BigDecimal mid) {
        BigDecimal factor = BigDecimal.ONE.subtract(props.spreadPercent().divide(HUNDRED, 8, RoundingMode.HALF_EVEN));
        return mid.multiply(factor).setScale(8, RoundingMode.HALF_EVEN);
    }

    private QuoteDto toDto(FxQuote q) {
        return new QuoteDto(q.getId(), q.getReference(), q.getSourceCurrency(), q.getTargetCurrency(),
                q.getSourceAmount(), q.getFee(), q.getSourceAmount().add(q.getFee()), q.getMidRate(), q.getRate(),
                q.getTargetAmount(), q.getExpiresAt(), q.getUsedAt() != null, q.getCreatedAt());
    }

    /**
     * @param source       OPEN_ER_API (live feed), MANUAL (admin) or CONFIG_DEFAULT (nothing stored yet)
     * @param asOf         when PayBridge stored this rate
     * @param stale        true when quoting is paused because the feed has not refreshed within max-age
     */
    public record RateCard(String pair, BigDecimal midRate, BigDecimal customerRate, BigDecimal spreadPercent,
                           BigDecimal feeUsd, long quoteValiditySeconds, String source, Instant asOf,
                           boolean stale) {}
}

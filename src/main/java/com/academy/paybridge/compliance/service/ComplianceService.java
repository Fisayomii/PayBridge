package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@EnableConfigurationProperties(ComplianceProperties.class)
public class ComplianceService implements ComplianceApi {

    private static final Logger log = LogManager.getLogger(ComplianceService.class);

    private final ComplianceProperties props;
    private final SanctionsListService sanctions;

    public ComplianceService(ComplianceProperties props, SanctionsListService sanctions) {
        this.props = props;
        this.sanctions = sanctions;
    }

    @Override
    public void screenTransfer(TransferScreening s) {
        Set<String> sanctioned = props.sanctionedWallets() == null ? Set.of() : props.sanctionedWallets();
        if (sanctioned.contains(s.sourceWalletNumber().toUpperCase(Locale.ROOT))
                || sanctioned.contains(s.destination().toUpperCase(Locale.ROOT))) {
            log.warn("COMPLIANCE sanctions hit: {} -> {}", s.sourceWalletNumber(), s.destination());
            throw PayBridgeException.unprocessable("COMPLIANCE_BLOCK: This transfer cannot be processed");
        }

        if (s.narration() != null && props.blockedNarrationKeywords() != null) {
            String narration = s.narration().toUpperCase(Locale.ROOT);
            for (String keyword : props.blockedNarrationKeywords()) {
                if (narration.contains(keyword.toUpperCase(Locale.ROOT))) {
                    log.warn("COMPLIANCE narration keyword hit from {}", s.sourceWalletNumber());
                    throw PayBridgeException.unprocessable("COMPLIANCE_BLOCK: This transfer cannot be processed");
                }
            }
        }

        ComplianceProperties.TierLimits limits = props.limitsFor(s.senderKycTier());
        if (s.amount().compareTo(limits.singleTransferLimit()) > 0) {
            throw PayBridgeException.unprocessable("LIMIT_EXCEEDED: Your single transfer limit is NGN "
                    + limits.singleTransferLimit().toPlainString() + " (KYC tier " + s.senderKycTier()
                    + "). Upgrade your KYC tier to send more.");
        }
        BigDecimal projected = s.outgoingToday().add(s.amount());
        if (projected.compareTo(limits.dailyTransferLimit()) > 0) {
            BigDecimal remaining = limits.dailyTransferLimit().subtract(s.outgoingToday()).max(BigDecimal.ZERO);
            throw PayBridgeException.unprocessable("LIMIT_EXCEEDED: Daily transfer limit is NGN "
                    + limits.dailyTransferLimit().toPlainString() + "; you can send NGN "
                    + remaining.toPlainString() + " more today.");
        }

        ComplianceProperties.Velocity velocity = props.velocity();
        if (velocity != null && s.recentTransferCount() >= velocity.maxTransfers()) {
            log.warn("COMPLIANCE velocity limit hit for {}: {} transfers in {}",
                    s.sourceWalletNumber(), s.recentTransferCount(), velocity.window());
            throw new PayBridgeException("VELOCITY_LIMIT: Too many transfers in a short time. Please wait and try again.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Override
    public void checkInboundCredit(int kycTier, BigDecimal currentBalance, BigDecimal amount) {
        BigDecimal maxBalance = props.limitsFor(kycTier).maxBalance();
        if (maxBalance != null && currentBalance.add(amount).compareTo(maxBalance) > 0) {
            throw PayBridgeException.unprocessable("LIMIT_EXCEEDED: The receiving wallet cannot hold more than NGN "
                    + maxBalance.toPlainString() + " at its KYC tier");
        }
    }

    @Override
    public Duration velocityWindow() {
        return props.velocity() == null ? Duration.ofMinutes(10) : props.velocity().window();
    }

    @Override
    public ScreeningResult screenRemittance(RemittanceScreening s) {
        ComplianceProperties.Remittance rules = props.remittance();
        List<String> block = new ArrayList<>();
        List<String> review = new ArrayList<>();

        if (rules != null && rules.blockedCountries() != null
                && rules.blockedCountries().contains(s.senderCountry().toUpperCase(Locale.ROOT))) {
            block.add("Sender country " + s.senderCountry().toUpperCase(Locale.ROOT) + " is under comprehensive sanctions");
        }
        if (s.purpose() != null && props.blockedNarrationKeywords() != null) {
            String purpose = s.purpose().toUpperCase(Locale.ROOT);
            for (String keyword : props.blockedNarrationKeywords()) {
                if (purpose.contains(keyword.toUpperCase(Locale.ROOT))) {
                    block.add("Purpose contains blocked keyword");
                }
            }
        }
        if (rules != null && rules.watchlist() != null) {
            for (String listed : rules.watchlist()) {
                if (nameMatches(s.senderName(), listed)) {
                    review.add("Possible watchlist match for sender: " + listed);
                }
                if (nameMatches(s.recipientName(), listed) || nameMatches(s.recipientAccountName(), listed)) {
                    review.add("Possible watchlist match for recipient: " + listed);
                }
            }
        }
        if (sanctions.enabled()) {
            if (!sanctions.isLoaded()) {
                // Fail closed: never wave money through because the list could not be loaded.
                review.add("OFAC sanctions list not loaded: manual screening required");
            } else {
                screenOfac("Sender", s.senderName(), review);
                screenOfac("Recipient", s.recipientName(), review);
                if (!sameWords(s.recipientName(), s.recipientAccountName())) {
                    screenOfac("Recipient wallet holder", s.recipientAccountName(), review);
                }
            }
        }
        if (rules != null && rules.reviewThresholdUsd() != null
                && s.amountUsd().compareTo(rules.reviewThresholdUsd()) >= 0) {
            review.add("Amount USD " + s.amountUsd().toPlainString() + " is at or above the review threshold of USD "
                    + rules.reviewThresholdUsd().toPlainString());
        }
        if (!sharesAName(s.recipientName(), s.recipientAccountName())) {
            review.add("Recipient name '" + s.recipientName() + "' does not match the wallet name '"
                    + s.recipientAccountName() + "'");
        }

        if (!block.isEmpty()) {
            log.warn("COMPLIANCE remittance blocked: {}", block);
            return new ScreeningResult(Decision.BLOCK, List.copyOf(block));
        }
        if (!review.isEmpty()) {
            log.info("COMPLIANCE remittance needs review: {}", review);
            return new ScreeningResult(Decision.REVIEW, List.copyOf(review));
        }
        return new ScreeningResult(Decision.CLEAR, List.of());
    }

    private void screenOfac(String party, String name, List<String> review) {
        for (OfacSanctionsList.Hit hit : sanctions.screen(name)) {
            review.add(party + " '" + name + "': " + hit.describe());
        }
    }

    private static boolean sameWords(String a, String b) {
        return words(a).equals(words(b));
    }

    /** Every word of the listed name appears in the candidate, in any order ("Doe John" matches "John Doe"). */
    static boolean nameMatches(String candidate, String listed) {
        Set<String> words = words(candidate);
        Set<String> listedWords = words(listed);
        return !listedWords.isEmpty() && words.containsAll(listedWords);
    }

    /** Loose name check between what the partner sent and the wallet's name: at least one word in common. */
    static boolean sharesAName(String a, String b) {
        Set<String> left = words(a);
        left.retainAll(words(b));
        return !left.isEmpty();
    }

    private static Set<String> words(String name) {
        Set<String> out = new HashSet<>();
        if (name == null) return out;
        for (String w : name.toUpperCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.length() > 1) out.add(w);
        }
        return out;
    }
}

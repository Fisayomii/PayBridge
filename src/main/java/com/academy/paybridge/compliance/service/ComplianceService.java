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
import java.util.Locale;
import java.util.Set;

@Service
@EnableConfigurationProperties(ComplianceProperties.class)
public class ComplianceService implements ComplianceApi {

    private static final Logger log = LogManager.getLogger(ComplianceService.class);

    private final ComplianceProperties props;

    public ComplianceService(ComplianceProperties props) {
        this.props = props;
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
}

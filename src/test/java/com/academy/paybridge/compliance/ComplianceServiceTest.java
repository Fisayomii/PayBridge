package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.ComplianceApi.Decision;
import com.academy.paybridge.compliance.api.ComplianceApi.RemittanceScreening;
import com.academy.paybridge.compliance.api.ComplianceApi.TransferScreening;
import com.academy.paybridge.compliance.service.ComplianceProperties;
import com.academy.paybridge.compliance.service.ComplianceProperties.TierLimits;
import com.academy.paybridge.compliance.service.ComplianceService;
import com.academy.paybridge.compliance.service.OfacProperties;
import com.academy.paybridge.compliance.service.SanctionsListService;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceServiceTest {

    private final ComplianceService compliance = new ComplianceService(new ComplianceProperties(
            Map.of(1, new TierLimits(bd("50000"), bd("50000"), bd("300000")),
                   3, new TierLimits(bd("5000000"), bd("25000000"), null)),
            new ComplianceProperties.Velocity(3, Duration.ofMinutes(10)),
            Set.of("WALLET-9999"),
            Set.of("FRAUD"),
            new ComplianceProperties.Remittance(Set.of("KP", "IR"), List.of("Viktor Sanctionov"), bd("3000"))),
            new SanctionsListService(OfacProperties.disabled(), null));

    @Test
    void velocityLimitReturns429OnceTheWindowIsFull() {
        assertThatCode(() -> compliance.screenTransfer(screening("100", "0", 2))).doesNotThrowAnyException();
        assertThatThrownBy(() -> compliance.screenTransfer(screening("100", "0", 3)))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void singleAndDailyLimitsAreInclusive() {
        assertThatCode(() -> compliance.screenTransfer(screening("50000", "0", 0))).doesNotThrowAnyException();
        assertThatThrownBy(() -> compliance.screenTransfer(screening("50000.01", "0", 0))).hasMessageContaining("single");
        assertThatCode(() -> compliance.screenTransfer(screening("10000", "40000", 0))).doesNotThrowAnyException();
        assertThatThrownBy(() -> compliance.screenTransfer(screening("10000.01", "40000", 0))).hasMessageContaining("Daily");
    }

    @Test
    void sanctionsAndKeywordsBlockWithoutRevealingWhy() {
        assertThatThrownBy(() -> compliance.screenTransfer(new TransferScreening("9000000001", "WALLET-9999",
                bd("10"), "hi", 1, BigDecimal.ZERO, 0))).hasMessage("COMPLIANCE_BLOCK: This transfer cannot be processed");
        assertThatThrownBy(() -> compliance.screenTransfer(new TransferScreening("9000000001", "9000000002",
                bd("10"), "fraud proceeds", 1, BigDecimal.ZERO, 0))).hasMessageContaining("COMPLIANCE_BLOCK");
    }

    @Test
    void maxBalanceAppliesOnlyWhereConfigured() {
        assertThatCode(() -> compliance.checkInboundCredit(1, bd("299999.99"), bd("0.01"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> compliance.checkInboundCredit(1, bd("299999.99"), bd("0.02")))
                .isInstanceOf(PayBridgeException.class);
        assertThatCode(() -> compliance.checkInboundCredit(3, bd("999999999"), bd("1"))).doesNotThrowAnyException();
    }

    @Test
    void remittanceScreeningClearsReviewsOrBlocks() {
        assertThat(compliance.screenRemittance(remit("Grace Okafor", "US", "Ada Obi", "Ada Chioma Obi", "200", "Family support"))
                .decision()).isEqualTo(Decision.CLEAR);
        // Word order and case do not hide a watchlist name.
        assertThat(compliance.screenRemittance(remit("SANCTIONOV viktor", "GB", "Ada Obi", "Ada Obi", "200", "Gift"))
                .decision()).isEqualTo(Decision.REVIEW);
        assertThat(compliance.screenRemittance(remit("Grace Okafor", "US", "Ada Obi", "Ada Obi", "3000", "School fees"))
                .reasons()).anyMatch(r -> r.contains("review threshold"));
        assertThat(compliance.screenRemittance(remit("Grace Okafor", "US", "Tunde Bello", "Ada Obi", "50", "Gift"))
                .reasons()).anyMatch(r -> r.contains("does not match"));
        assertThat(compliance.screenRemittance(remit("Grace Okafor", "ir", "Ada Obi", "Ada Obi", "50", "Gift"))
                .decision()).isEqualTo(Decision.BLOCK);
    }

    private static RemittanceScreening remit(String sender, String country, String recipient, String walletName,
                                             String amount, String purpose) {
        return new RemittanceScreening(sender, country, recipient, walletName, bd(amount), purpose);
    }

    private static TransferScreening screening(String amount, String outgoingToday, long recent) {
        return new TransferScreening("9000000001", "9000000002", bd(amount), "rent", 1, bd(outgoingToday), recent);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}

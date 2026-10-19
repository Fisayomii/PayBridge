package com.academy.paybridge.customer;

import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.customer.service.AuthService;
import com.academy.paybridge.customer.service.KycService;
import com.academy.paybridge.customer.service.KycService.VerificationResult;
import com.academy.paybridge.fx.client.RateFeed.FeedRate;
import com.academy.paybridge.fx.service.FxService;
import com.academy.paybridge.settlement.service.LiquidityService;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.Role;
import com.academy.paybridge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BVN verification (simulated identity provider: every BVN belongs to "Ada Obi"), the rate feed guard, liquidity. */
class KycAndLiveDataIntegrationTest extends IntegrationTest {

    @Autowired KycService kycService;
    @Autowired FxService fxService;
    @Autowired LiquidityService liquidityService;

    @Test
    void matchingBvnUpgradesToTierTwoAndNeverStoresTheRawNumber() {
        AuthenticatedUser ada = named("Ada Chioma Obi");
        String bvn = randomBvn();

        VerificationResult result = kycService.verifyBvn(ada, bvn);

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.tierBefore()).isEqualTo(1);
        assertThat(result.tierAfter()).isEqualTo(2);
        assertThat(customerService.list(0, 1000).getContent()).filteredOn(c -> c.id().equals(ada.customerId()))
                .singleElement().satisfies(c -> assertThat(c.kycTier()).isEqualTo(2));
        assertThat(jdbc.queryForObject("select count(*) from kyc_verifications where id_number_mask = ? "
                + "or id_number_hash = ?", Integer.class, bvn, bvn)).isZero();
        assertThat(result.idNumber()).isEqualTo(bvn.substring(0, 3) + "*****" + bvn.substring(8));

        assertStatus(() -> kycService.verifyBvn(ada, randomBvn()), HttpStatus.CONFLICT);
    }

    @Test
    void nameMismatchOrUnknownBvnFailsAndKeepsTierOne() {
        AuthenticatedUser other = named("Tunde Bello");
        VerificationResult mismatch = kycService.verifyBvn(other, randomBvn());
        assertThat(mismatch.status()).isEqualTo("FAILED");
        assertThat(mismatch.failureReason()).contains("does not match");
        assertThat(mismatch.tierAfter()).isEqualTo(1);

        AuthenticatedUser ada = named("Ada Obi");
        VerificationResult unknown = kycService.verifyBvn(ada, "00000000000");
        assertThat(unknown.status()).isEqualTo("FAILED");
        assertThat(kycService.history(ada)).hasSize(1);
    }

    @Test
    void oneBvnCannotVerifyTwoProfiles() {
        String bvn = randomBvn();
        assertThat(kycService.verifyBvn(named("Ada Obi"), bvn).status()).isEqualTo("VERIFIED");

        VerificationResult second = kycService.verifyBvn(named("Ada Obi"), bvn);
        assertThat(second.status()).isEqualTo("FAILED");
        assertThat(second.failureReason()).contains("already linked");
    }

    @Test
    void repeatedFailuresAreRateLimited() {
        AuthenticatedUser tunde = named("Tunde Bello");
        for (int i = 0; i < 5; i++) {
            kycService.verifyBvn(tunde, randomBvn());
        }
        assertStatus(() -> kycService.verifyBvn(tunde, randomBvn()), HttpStatus.TOO_MANY_REQUESTS);
        assertStatus(() -> kycService.verifyBvn(tunde, "123"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void feedTickBeyondTheDeviationGuardIsIgnored() {
        BigDecimal before = fxService.currentMidRate();
        // The very first stored rate is accepted unguarded, so make sure one exists (a fresh database has none).
        assertThat(fxService.publishFromFeed(new FeedRate(before, Instant.now()), "TEST_FEED")).isTrue();
        boolean accepted = fxService.publishFromFeed(
                new FeedRate(before.multiply(new BigDecimal("1.5")), Instant.now()), "TEST_FEED");
        assertThat(accepted).isFalse();
        assertThat(fxService.currentMidRate()).isEqualByComparingTo(before);

        assertThat(fxService.publishFromFeed(new FeedRate(before, Instant.now()), "TEST_FEED")).isTrue();
        assertThat(fxService.rateCard().source()).isEqualTo("TEST_FEED");
    }

    @Test
    void liquidityComparesProviderBalanceWithPayoutsInFlight() {
        LiquidityService.LiquidityView view = liquidityService.ngnLiquidity();

        assertThat(view.provider()).isEqualTo("SIMULATED");
        assertThat(view.payoutsInFlight()).isEqualByComparingTo(balance(InternalAccounts.NGN_PAYOUT_CLEARING));
        assertThat(view.headroom()).isEqualByComparingTo(view.providerBalance().subtract(view.payoutsInFlight()));
    }

    private AuthenticatedUser named(String fullName) {
        String email = "k-" + UUID.randomUUID() + "@test.local";
        AuthService.Registration r = authService.register(email, fullName, PASSWORD);
        return new AuthenticatedUser(r.customer().id(), email, Role.CUSTOMER);
    }

    private static String randomBvn() {
        return "2" + String.format("%010d", ThreadLocalRandom.current().nextLong(1, 10_000_000_000L));
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getStatus()).isEqualTo(expected);
    }
}

package com.academy.paybridge.funding;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.funding.client.SimulatedCollectionGateway;
import com.academy.paybridge.funding.domain.FundingDirection;
import com.academy.paybridge.funding.service.FundingService;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FundingIntegrationTest extends IntegrationTest {

    @Autowired SimulatedCollectionGateway gateway;
    @Autowired AccountService accountService;

    @Test
    void newWalletsStartEmpty() {
        TestCustomer c = customer();
        assertThat(balance(c.wallet())).isEqualByComparingTo("0.00");
    }

    @Test
    void checkoutCreditsWalletOnlyAfterVerifiedPaymentAndOnlyOnce() {
        TestCustomer c = customer();
        BigDecimal collectionsBefore = balance(InternalAccounts.NGN_PAYSTACK_COLLECTIONS);

        FundingService.FundingDto checkout = fundingService.startCheckout(c.user(), c.wallet(), new BigDecimal("20000.00"), key());
        assertThat(checkout.status()).isEqualTo("PENDING");
        assertThat(checkout.authorizationUrl()).contains(checkout.reference());

        // Webhook arrives before payment: nothing happens.
        fundingService.confirmCheckout(checkout.reference());
        assertThat(balance(c.wallet())).isEqualByComparingTo("0.00");

        gateway.pay(checkout.reference(), true, null);
        fundingService.confirmCheckout(checkout.reference());
        fundingService.confirmCheckout(checkout.reference()); // duplicate webhook
        fundingService.verifyForUser(c.user(), checkout.reference()); // customer also checks

        assertThat(fundingService.getForUser(c.user(), checkout.reference()).status()).isEqualTo("SUCCESS");
        assertThat(balance(c.wallet())).isEqualByComparingTo("20000.00");
        assertThat(balance(InternalAccounts.NGN_PAYSTACK_COLLECTIONS).subtract(collectionsBefore))
                .isEqualByComparingTo("-20000.00");
    }

    @Test
    void amountMismatchIsNeverCredited() {
        TestCustomer c = customer();
        FundingService.FundingDto checkout = fundingService.startCheckout(c.user(), c.wallet(), new BigDecimal("20000.00"), key());

        gateway.pay(checkout.reference(), true, 100L); // provider says only NGN 1.00 was charged

        assertThat(fundingService.confirmCheckout(checkout.reference()).status()).isEqualTo("FAILED");
        assertThat(balance(c.wallet())).isEqualByComparingTo("0.00");
    }

    @Test
    void declinedPaymentFailsTheCheckout() {
        TestCustomer c = customer();
        FundingService.FundingDto checkout = fundingService.startCheckout(c.user(), c.wallet(), new BigDecimal("500.00"), key());
        gateway.pay(checkout.reference(), false, null);
        assertThat(fundingService.confirmCheckout(checkout.reference()).status()).isEqualTo("FAILED");
    }

    @Test
    void unpaidCheckoutsAreAbandonedByTheReconciler() {
        TestCustomer c = customer();
        FundingService.FundingDto checkout = fundingService.startCheckout(c.user(), c.wallet(), new BigDecimal("500.00"), key());

        fundingService.reconcileStaleCheckouts(Duration.ZERO, Duration.ZERO, 1000);

        assertThat(fundingService.getForUser(c.user(), checkout.reference()).status()).isEqualTo("ABANDONED");
    }

    @Test
    void checkoutRespectsKycMaxBalance() {
        TestCustomer c = customer("299000.00");
        assertThatThrownBy(() -> fundingService.startCheckout(c.user(), c.wallet(), new BigDecimal("1000.01"), key()))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("LIMIT_EXCEEDED");
    }

    @Test
    void cannotTopUpOrViewSomeoneElsesWallet() {
        TestCustomer owner = customer();
        TestCustomer other = customer();
        assertThatThrownBy(() -> fundingService.startCheckout(other.user(), owner.wallet(), new BigDecimal("500.00"), key()))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);

        FundingService.FundingDto mine = fundingService.startCheckout(owner.user(), owner.wallet(), new BigDecimal("500.00"), key());
        assertThatThrownBy(() -> fundingService.getForUser(other.user(), mine.reference()))
                .extracting(e -> ((PayBridgeException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void frozenWalletCannotStartCheckoutButPaidMoneyStillLands() {
        TestCustomer c = customer();
        FundingService.FundingDto checkout = fundingService.startCheckout(c.user(), c.wallet(), new BigDecimal("500.00"), key());
        accountService.changeStatus(c.wallet(), AccountStatus.FROZEN, "review", admin().customerId());

        assertThatThrownBy(() -> fundingService.startCheckout(c.user(), c.wallet(), new BigDecimal("500.00"), key()))
                .isInstanceOf(PayBridgeException.class);

        gateway.pay(checkout.reference(), true, null);
        fundingService.confirmCheckout(checkout.reference());
        assertThat(balance(c.wallet())).isEqualByComparingTo("500.00");
    }

    @Test
    void adminAdjustmentsAreLedgeredAndIdempotent() {
        TestCustomer c = customer();
        String key = key();
        fundingService.adjust(admin(), c.wallet(), FundingDirection.CREDIT, new BigDecimal("700.00"), "goodwill", key);
        fundingService.adjust(admin(), c.wallet(), FundingDirection.CREDIT, new BigDecimal("700.00"), "goodwill", key);
        assertThat(balance(c.wallet())).isEqualByComparingTo("700.00");

        fundingService.adjust(admin(), c.wallet(), FundingDirection.DEBIT, new BigDecimal("200.00"), "correction", key());
        assertThat(balance(c.wallet())).isEqualByComparingTo("500.00");

        assertThatThrownBy(() -> fundingService.adjust(admin(), c.wallet(), FundingDirection.DEBIT,
                new BigDecimal("500.01"), "too much", key()))
                .isInstanceOf(PayBridgeException.class);
    }
}

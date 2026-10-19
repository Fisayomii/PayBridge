package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.customer.service.AuthService;
import com.academy.paybridge.fx.api.QuoteDto;
import com.academy.paybridge.fx.service.FxService;
import com.academy.paybridge.remittance.api.RemittanceDto;
import com.academy.paybridge.remittance.service.RemittanceService;
import com.academy.paybridge.remittance.service.RemittanceService.CreateRemittanceCommand;
import com.academy.paybridge.settlement.domain.FxDeal;
import com.academy.paybridge.settlement.domain.ReconciliationBreak;
import com.academy.paybridge.settlement.service.ReconciliationService;
import com.academy.paybridge.settlement.service.TreasuryService;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.Role;
import com.academy.paybridge.support.IntegrationTest;
import com.academy.paybridge.transfer.api.ExternalTransferCommand;
import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.SimulatedTransferGateway;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutStatus;
import com.academy.paybridge.transfer.service.PayoutProcessor;
import com.academy.paybridge.transfer.service.TransferService;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RemittanceSettlementIntegrationTest extends IntegrationTest {

    @Autowired RemittanceService remittanceService;
    @Autowired TreasuryService treasuryService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired FxService fxService;
    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired PayoutProcessor payoutProcessor;
    @Autowired SimulatedTransferGateway transferGateway;
    @Autowired MockMvc mvc;

    // ------------------------------------------------------------------ happy path

    @Test
    void clearRemittanceCreditsTheWalletAtTheQuotedRateAndChargesThePartner() {
        Partner p = partner("1000.00");
        TestCustomer recipient = customer();
        BigDecimal fxUsdBefore = balance(InternalAccounts.USD_FX_POSITION);
        BigDecimal feesBefore = balance(InternalAccounts.USD_REMITTANCE_FEE_INCOME);

        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("100.00"));
        assertThat(quote.rate()).isLessThan(quote.midRate()); // the spread
        assertThat(quote.targetAmount()).isEqualByComparingTo(
                quote.sourceAmount().multiply(quote.rate()).setScale(2, RoundingMode.DOWN));

        RemittanceDto r = remittanceService.create(p.user(), command(quote, recipient), key());

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(balance(recipient.wallet())).isEqualByComparingTo(quote.targetAmount());
        assertThat(balance(p.account())).isEqualByComparingTo(new BigDecimal("1000.00").subtract(quote.totalCost()));
        assertThat(balance(InternalAccounts.USD_FX_POSITION).subtract(fxUsdBefore)).isEqualByComparingTo("100.00");
        assertThat(balance(InternalAccounts.USD_REMITTANCE_FEE_INCOME).subtract(feesBefore))
                .isEqualByComparingTo(quote.fee());
    }

    @Test
    void retriesAreIdempotentAndAKeyCannotBeReusedForADifferentRemittance() {
        Partner p = partner("1000.00");
        TestCustomer recipient = customer();
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("50.00"));
        String key = key();

        RemittanceDto first = remittanceService.create(p.user(), command(quote, recipient), key);
        RemittanceDto retry = remittanceService.create(p.user(), command(quote, recipient), key);

        assertThat(retry.reference()).isEqualTo(first.reference());
        assertThat(balance(p.account())).isEqualByComparingTo(new BigDecimal("1000.00").subtract(quote.totalCost()));

        QuoteDto other = remittanceService.quote(p.user(), new BigDecimal("60.00"));
        assertStatus(() -> remittanceService.create(p.user(), command(other, recipient), key), HttpStatus.CONFLICT);
    }

    @Test
    void aQuoteIsHonouredOnceAndOnlyUntilItExpires() {
        Partner p = partner("1000.00");
        TestCustomer recipient = customer();
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("20.00"));
        remittanceService.create(p.user(), command(quote, recipient), key());
        assertThatThrownBy(() -> remittanceService.create(p.user(), command(quote, recipient), key()))
                .hasMessageContaining("QUOTE_USED");

        QuoteDto stale = remittanceService.quote(p.user(), new BigDecimal("20.00"));
        jdbc.update("update fx_quotes set expires_at = now() - interval '1 second' where reference = ?", stale.reference());
        assertThatThrownBy(() -> remittanceService.create(p.user(), command(stale, recipient), key()))
                .hasMessageContaining("QUOTE_EXPIRED");

        Partner other = partner("1000.00");
        QuoteDto mine = remittanceService.quote(p.user(), new BigDecimal("20.00"));
        assertStatus(() -> remittanceService.create(other.user(), command(mine, recipient), key()), HttpStatus.NOT_FOUND);
    }

    @Test
    void insufficientPrefundChargesNothingAndLeavesTheQuoteUsable() {
        Partner p = partner("50.00");
        TestCustomer recipient = customer();
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("100.00"));

        assertThatThrownBy(() -> remittanceService.create(p.user(), command(quote, recipient), key()))
                .hasMessageContaining("INSUFFICIENT_PREFUND");
        assertThat(balance(p.account())).isEqualByComparingTo("50.00");

        treasuryService.recordPrefund(p.account(), new BigDecimal("100.00"), "WIRE-" + UUID.randomUUID(), admin().customerId());
        assertThat(remittanceService.create(p.user(), command(quote, recipient), key()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void amountsOutsideTheCorridorLimitsAreRefused() {
        Partner p = partner("0");
        assertStatus(() -> remittanceService.quote(p.user(), new BigDecimal("9.99")), HttpStatus.UNPROCESSABLE_CONTENT);
        assertStatus(() -> remittanceService.quote(p.user(), new BigDecimal("10000.01")), HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ------------------------------------------------------------------ screening and review

    @Test
    void watchlistHitIsHeldThenApprovedByAnAnalyst() {
        Partner p = partner("1000.00");
        TestCustomer recipient = customer();
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("100.00"));
        BigDecimal clearingBefore = balance(InternalAccounts.USD_REMITTANCE_CLEARING);

        RemittanceDto held = remittanceService.create(p.user(), command(quote, recipient, "Viktor Sanctionov", "US"), key());

        assertThat(held.status()).isEqualTo("PENDING_REVIEW");
        assertThat(held.screeningNotes()).contains("watchlist");
        assertThat(balance(recipient.wallet())).isEqualByComparingTo("0.00");
        assertThat(balance(InternalAccounts.USD_REMITTANCE_CLEARING).subtract(clearingBefore))
                .isEqualByComparingTo(quote.totalCost());

        RemittanceDto approved = remittanceService.approve(held.reference(), admin().customerId(), "False positive");
        assertThat(approved.status()).isEqualTo("COMPLETED");
        assertThat(balance(recipient.wallet())).isEqualByComparingTo(quote.targetAmount());
        assertThat(balance(InternalAccounts.USD_REMITTANCE_CLEARING)).isEqualByComparingTo(clearingBefore);

        assertStatus(() -> remittanceService.reject(held.reference(), admin().customerId(), "too late"), HttpStatus.CONFLICT);
    }

    @Test
    void rejectedReviewRefundsThePartnerInFull() {
        Partner p = partner("5000.00");
        TestCustomer recipient = customerAtTier(3, "0");
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("3000.00")); // at the review threshold

        RemittanceDto held = remittanceService.create(p.user(), command(quote, recipient), key());
        assertThat(held.status()).isEqualTo("PENDING_REVIEW");
        assertThat(held.screeningNotes()).contains("review threshold");
        assertThat(balance(p.account())).isEqualByComparingTo(new BigDecimal("5000.00").subtract(quote.totalCost()));

        RemittanceDto rejected = remittanceService.reject(held.reference(), admin().customerId(), "Source of funds unclear");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(balance(p.account())).isEqualByComparingTo("5000.00");
        assertThat(balance(recipient.wallet())).isEqualByComparingTo("0.00");
    }

    @Test
    void blockedCountryIsRejectedWithoutCharging() {
        Partner p = partner("1000.00");
        TestCustomer recipient = customer();
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("100.00"));

        RemittanceDto r = remittanceService.create(p.user(), command(quote, recipient, "Grace Okafor", "KP"), key());

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(balance(p.account())).isEqualByComparingTo("1000.00");
        assertThat(balance(recipient.wallet())).isEqualByComparingTo("0.00");
    }

    @Test
    void approvalFailsAndRefundsIfTheWalletWasFrozenWhileInReview() {
        Partner p = partner("1000.00");
        TestCustomer recipient = customer();
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("100.00"));
        RemittanceDto held = remittanceService.create(p.user(), command(quote, recipient, "Viktor Sanctionov", "US"), key());

        accountService.changeStatus(recipient.wallet(), AccountStatus.FROZEN, "investigation", admin().customerId());
        RemittanceDto result = remittanceService.approve(held.reference(), admin().customerId(), "cleared");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).contains("RECIPIENT_UNAVAILABLE");
        assertThat(balance(p.account())).isEqualByComparingTo("1000.00");
        assertThat(balance(recipient.wallet())).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ settlement

    @Test
    void prefundsAreIdempotentOnTheBankReference() {
        Partner p = partner("0");
        String wire = "WIRE-" + UUID.randomUUID();
        treasuryService.recordPrefund(p.account(), new BigDecimal("500.00"), wire, admin().customerId());
        treasuryService.recordPrefund(p.account(), new BigDecimal("500.00"), wire, admin().customerId());
        assertThat(balance(p.account())).isEqualByComparingTo("500.00");

        assertStatus(() -> treasuryService.recordPrefund(p.account(), new BigDecimal("900.00"), wire, admin().customerId()),
                HttpStatus.CONFLICT);
    }

    @Test
    void fxDealClosesBothPositionsAndBooksTheGainOrLoss() {
        Partner p = partner("1000.00");
        TestCustomer recipient = customer();
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("100.00"));
        remittanceService.create(p.user(), command(quote, recipient), key());

        TreasuryService.FxPosition before = treasuryService.position();
        BigDecimal pnlBefore = balance(InternalAccounts.NGN_FX_PNL);
        BigDecimal executed = quote.midRate(); // the bank pays mid; we paid recipients mid minus spread

        FxDeal deal = treasuryService.bookFxDeal(executed, "DEAL-" + UUID.randomUUID(), admin().customerId());

        BigDecimal expectedPnl = before.usdOpen().multiply(executed).setScale(2, RoundingMode.HALF_EVEN)
                .subtract(before.ngnPaidOut());
        assertThat(deal.getUsdAmount()).isEqualByComparingTo(before.usdOpen());
        assertThat(deal.getRealisedPnl()).isEqualByComparingTo(expectedPnl);
        assertThat(balance(InternalAccounts.USD_FX_POSITION)).isEqualByComparingTo("0.00");
        assertThat(balance(InternalAccounts.NGN_FX_POSITION)).isEqualByComparingTo("0.00");
        assertThat(balance(InternalAccounts.NGN_FX_PNL).subtract(pnlBefore)).isEqualByComparingTo(expectedPnl);

        assertThatThrownBy(() -> treasuryService.bookFxDeal(executed, "DEAL-" + UUID.randomUUID(), admin().customerId()))
                .hasMessageContaining("no open USD position");
    }

    @Test
    void rateDeviationGuardRefusesFatFingersUnlessForced() {
        BigDecimal mid = fxService.currentMidRate();
        assertStatus(() -> fxService.publishMidRate(mid.multiply(new BigDecimal("1.5")), admin().customerId(), false),
                HttpStatus.CONFLICT);
        assertThat(fxService.publishMidRate(mid, admin().customerId(), false).midRate()).isEqualByComparingTo(mid);
    }

    @Test
    void integrityCheckExplainsEveryClearingBalance() {
        Partner p = partner("1000.00");
        QuoteDto quote = remittanceService.quote(p.user(), new BigDecimal("100.00"));
        remittanceService.create(p.user(), command(quote, customer(), "Viktor Sanctionov", "US"), key()); // stays held

        ReconciliationService.IntegrityReport report = reconciliationService.integrityCheck();

        assertThat(report.checks()).filteredOn(c -> !c.name().equals("Open reconciliation breaks"))
                .allSatisfy(c -> assertThat(c.passed()).as(c.name() + " " + c.actual()).isTrue());
        assertThat(reconciliationService.trialBalance().currencies())
                .allSatisfy(s -> assertThat(s.balanced()).as(s.currency()).isTrue());
    }

    @Test
    void lostReversalWebhookIsCaughtByReconciliationAndFixedByAcceptingTheProvider() {
        TestCustomer c = customer("20000.00");
        TransferDto payout = transferService.transferExternal(new ExternalTransferCommand(c.id(), c.wallet(),
                "0123456789", "058", null, new BigDecimal("5000.00"), "rent", key(), false));
        payoutProcessor.applyGatewayResult(payout.reference(), PayoutStatus.SUCCESS, null, "ok");
        BigDecimal afterSuccess = balance(c.wallet());

        // The bank returns the money, but the transfer.reversed webhook never reaches us.
        transferGateway.settle(payout.reference(), PayoutStatus.REVERSED, "Beneficiary account closed");

        ReconciliationService.PayoutReconciliationResult run =
                reconciliationService.reconcilePayouts(LocalDate.now(ReconciliationService.LAGOS), admin().customerId());
        ReconciliationBreak found = reconciliationService.listBreaks(ReconciliationBreak.Status.OPEN).stream()
                .filter(b -> b.getReference().equals(payout.reference())).findFirst().orElseThrow();
        assertThat(run.checked()).isPositive();
        assertThat(found.getBreakType()).isEqualTo(ReconciliationBreak.Type.STATUS_MISMATCH);
        assertThat(found.getLedgerStatus()).isEqualTo("SUCCESS");
        assertThat(found.getProviderStatus()).isEqualTo("REVERSED");

        // Re-running does not duplicate the open break.
        reconciliationService.reconcilePayouts(LocalDate.now(ReconciliationService.LAGOS), admin().customerId());
        assertThat(reconciliationService.listBreaks(ReconciliationBreak.Status.OPEN))
                .filteredOn(b -> b.getReference().equals(payout.reference())).hasSize(1);

        reconciliationService.resolveBreak(found.getId(), ReconciliationBreak.Resolution.ACCEPT_PROVIDER,
                "Provider confirms reversal", admin().customerId());

        assertThat(transferService.getByReference(payout.reference()).status()).isEqualTo("REVERSED");
        assertThat(balance(c.wallet()).subtract(afterSuccess)).isEqualByComparingTo(payout.totalDebit());
    }

    // ------------------------------------------------------------------ access control

    @Test
    void partnersAndCustomersStayInTheirOwnLanes() throws Exception {
        Partner p = partner("0");
        String partnerToken = authService.login(p.email(), PASSWORD).accessToken();
        TestCustomer c = customer();
        String customerToken = authService.login(c.email(), PASSWORD).accessToken();

        mvc.perform(get("/api/v1/partner/account").header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/transfers").header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/partner/account").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/settlement/trial-balance").header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private record Partner(AuthenticatedUser user, String account, String email) {}

    private Partner partner(String prefund) {
        String email = "p-" + UUID.randomUUID() + "@test.local";
        AuthService.Registration r = authService.create(email, "Partner " + email.substring(2, 10), PASSWORD, Role.PARTNER);
        Partner p = new Partner(new AuthenticatedUser(r.customer().id(), email, Role.PARTNER),
                r.wallet().walletNumber(), email);
        if (new BigDecimal(prefund).signum() > 0) {
            treasuryService.recordPrefund(p.account(), new BigDecimal(prefund), "WIRE-" + UUID.randomUUID(),
                    admin().customerId());
        }
        return p;
    }

    /** Customer wallets in tests are named "Test ...", so a recipient called "Test Recipient" passes the name check. */
    private static CreateRemittanceCommand command(QuoteDto quote, TestCustomer recipient) {
        return command(quote, recipient, "Grace Okafor", "US");
    }

    private static CreateRemittanceCommand command(QuoteDto quote, TestCustomer recipient, String sender, String country) {
        return new CreateRemittanceCommand(quote.reference(), null, sender, country, "US-DL-D1234567",
                "12 Main St, Houston, TX", "Test Recipient", recipient.wallet(), "Family support");
    }

    private static void assertStatus(ThrowableAssert.ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call)
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getStatus()).isEqualTo(expected);
    }
}

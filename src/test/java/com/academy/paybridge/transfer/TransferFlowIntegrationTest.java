package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.customer.api.CustomerStatus;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.support.IntegrationTest;
import com.academy.paybridge.transfer.api.ExternalTransferCommand;
import com.academy.paybridge.transfer.api.InternalTransferCommand;
import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutStatus;
import com.academy.paybridge.transfer.domain.TransferType;
import com.academy.paybridge.transfer.service.BeneficiaryService;
import com.academy.paybridge.transfer.service.PayoutProcessor;
import com.academy.paybridge.transfer.service.TransferService;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferFlowIntegrationTest extends IntegrationTest {

    private static final String BANK = "058";

    @Autowired TransferService transferService;
    @Autowired PayoutProcessor payoutProcessor;
    @Autowired BeneficiaryService beneficiaryService;
    @Autowired AccountService accountService;

    // ---------------------------------------------------------------- internal

    @Test
    void internalTransferMovesMoneyAndWritesBalancedLedger() {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer("50.00");

        TransferDto t = transferService.transferInternal(internal(a, b.wallet(), "250.00", key()));

        assertThat(t.status()).isEqualTo("SUCCESS");
        assertThat(t.fee()).isEqualByComparingTo("0.00");
        assertThat(balance(a.wallet())).isEqualByComparingTo("750.00");
        assertThat(balance(b.wallet())).isEqualByComparingTo("300.00");
    }

    @Test
    void cannotSendFromSomeoneElsesWallet() {
        TestCustomer victim = customer("1000.00");
        TestCustomer attacker = customer();

        assertStatus(() -> transferService.transferInternal(new InternalTransferCommand(attacker.id(),
                victim.wallet(), attacker.wallet(), null, new BigDecimal("500.00"), "mine now", key(), false)),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> transferService.transferExternal(new ExternalTransferCommand(attacker.id(),
                victim.wallet(), "0123456789", BANK, null, new BigDecimal("500.00"), "mine now", key(), false)),
                HttpStatus.NOT_FOUND);
        assertThat(balance(victim.wallet())).isEqualByComparingTo("1000.00");
    }

    @Test
    void cannotReadSomeoneElsesTransfer() {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer();
        TestCustomer snoop = customer();
        TransferDto t = transferService.transferInternal(internal(a, b.wallet(), "10.00", key()));

        assertStatus(() -> transferService.getForUser(snoop.user(), t.reference()), HttpStatus.NOT_FOUND);
        assertThat(transferService.getForUser(a.user(), t.reference()).reference()).isEqualTo(t.reference());
        assertThat(transferService.getForUser(admin(), t.reference()).reference()).isEqualTo(t.reference());
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsOriginalAndDoesNotDebitTwice() {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer();
        String key = key();

        TransferDto first = transferService.transferInternal(internal(a, b.wallet(), "100.00", key));
        TransferDto retry = transferService.transferInternal(internal(a, b.wallet(), "100.00", key));

        assertThat(retry.reference()).isEqualTo(first.reference());
        assertThat(balance(a.wallet())).isEqualByComparingTo("900.00");
    }

    @Test
    void reusingIdempotencyKeyForDifferentRequestIsRejected() {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer();
        String key = key();
        transferService.transferInternal(internal(a, b.wallet(), "100.00", key));

        assertStatus(() -> transferService.transferInternal(internal(a, b.wallet(), "999.00", key)), HttpStatus.CONFLICT);
        assertThat(balance(a.wallet())).isEqualByComparingTo("900.00");
    }

    @Test
    void insufficientFundsLeavesNoTrace() {
        TestCustomer a = customer("10.00");
        TestCustomer b = customer();

        assertStatus(() -> transferService.transferInternal(internal(a, b.wallet(), "10.01", key())),
                HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(balance(a.wallet())).isEqualByComparingTo("10.00");
        assertThat(balance(b.wallet())).isEqualByComparingTo("0.00");
    }

    @Test
    void customersCannotAddressInternalGlAccounts() {
        TestCustomer a = customer("1000.00");
        assertStatus(() -> transferService.transferInternal(internal(a, InternalAccounts.NGN_FEE_INCOME, "100.00", key())),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentDebitsNeverOverdrawTheWallet() throws Exception {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        runConcurrently(20, i -> {
            try {
                transferService.transferInternal(internal(a, b.wallet(), "100.00", key()));
                succeeded.incrementAndGet();
            } catch (PayBridgeException ex) {
                if (ex.getStatus() == HttpStatus.UNPROCESSABLE_CONTENT) insufficient.incrementAndGet();
                else throw ex;
            }
        });

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(10);
        assertThat(balance(a.wallet())).isEqualByComparingTo("0.00");
    }

    @Test
    void opposingConcurrentTransfersDoNotDeadlock() throws Exception {
        TestCustomer a = customer("10000.00");
        TestCustomer b = customer("10000.00");

        runConcurrently(30, i -> {
            if (i % 2 == 0) transferService.transferInternal(internal(a, b.wallet(), "10.00", key()));
            else transferService.transferInternal(internal(b, a.wallet(), "10.00", key()));
        });

        assertThat(balance(a.wallet()).add(balance(b.wallet()))).isEqualByComparingTo("20000.00");
    }

    @Test
    void concurrentRetriesWithSameKeyCreateExactlyOneTransfer() throws Exception {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer();
        String key = key();
        Set<String> references = ConcurrentHashMap.newKeySet();

        runConcurrently(10, i -> references.add(
                transferService.transferInternal(internal(a, b.wallet(), "100.00", key)).reference()));

        assertThat(references).hasSize(1);
        assertThat(balance(a.wallet())).isEqualByComparingTo("900.00");
    }

    // ---------------------------------------------------------------- limits

    @Test
    void tierOneSingleTransferLimitIsEnforced() {
        TestCustomer a = customer("60000.00");
        TestCustomer b = customerAtTier(3, "0");

        assertStatus(() -> transferService.transferInternal(internal(a, b.wallet(), "50000.01", key())),
                HttpStatus.UNPROCESSABLE_CONTENT);
        transferService.transferInternal(internal(a, b.wallet(), "50000.00", key()));
    }

    @Test
    void dailyLimitCountsEveryTransferTodayAcrossTypes() {
        TestCustomer a = customer("60000.00");
        TestCustomer b = customerAtTier(3, "0");

        transferService.transferInternal(internal(a, b.wallet(), "30000.00", key()));
        transferService.transferExternal(external(a, "0123456700", "15000.00", key())); // counts while pending

        assertThatThrownBy(() -> transferService.transferInternal(internal(a, b.wallet(), "5000.01", key())))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("5000.00 more today");
    }

    @Test
    void failedPayoutsDoNotUseUpTheDailyLimit() {
        TestCustomer a = customer("60000.00");
        TestCustomer b = customerAtTier(3, "0");

        TransferDto rejected = transferService.transferExternal(external(a, "8888888888", "40000.00", key()));
        assertThat(rejected.status()).isEqualTo("FAILED");

        transferService.transferInternal(internal(a, b.wallet(), "45000.00", key()));
    }

    @Test
    void upgradingKycTierRaisesLimits() {
        TestCustomer a = customerAtTier(2, "150000.00");
        TestCustomer b = customerAtTier(3, "0");
        transferService.transferInternal(internal(a, b.wallet(), "100000.00", key()));
        assertThat(balance(b.wallet())).isEqualByComparingTo("100000.00");
    }

    @Test
    void receiverMaxBalanceIsEnforced() {
        TestCustomer rich = customerAtTier(3, "500000.00");
        TestCustomer tierOne = customer("290000.00");

        assertStatus(() -> transferService.transferInternal(internal(rich, tierOne.wallet(), "10000.01", key())),
                HttpStatus.UNPROCESSABLE_CONTENT);
        transferService.transferInternal(internal(rich, tierOne.wallet(), "10000.00", key()));
        assertThat(balance(tierOne.wallet())).isEqualByComparingTo("300000.00");
    }

    @Test
    void suspendedCustomerCannotMoveMoney() {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer();
        customerService.changeStatus(a.id(), CustomerStatus.SUSPENDED, "fraud review", admin().customerId());

        assertStatus(() -> transferService.transferInternal(internal(a, b.wallet(), "10.00", key())), HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- freeze

    @Test
    void frozenWalletCannotSendOrReceiveButPostNoDebitCanReceive() {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer("1000.00");

        accountService.changeStatus(a.wallet(), AccountStatus.FROZEN, "investigation", admin().customerId());
        assertStatus(() -> transferService.transferInternal(internal(a, b.wallet(), "10.00", key())),
                HttpStatus.UNPROCESSABLE_CONTENT);
        assertStatus(() -> transferService.transferInternal(internal(b, a.wallet(), "10.00", key())),
                HttpStatus.UNPROCESSABLE_CONTENT);

        accountService.changeStatus(a.wallet(), AccountStatus.POST_NO_DEBIT, "partial release", admin().customerId());
        transferService.transferInternal(internal(b, a.wallet(), "10.00", key()));
        assertStatus(() -> transferService.transferInternal(internal(a, b.wallet(), "10.00", key())),
                HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(accountService.statusHistory(a.wallet())).hasSize(2);
    }

    @Test
    void refundStillReachesAWalletFrozenWhilePayoutWasInFlight() {
        TestCustomer a = customer("5000.00");
        TransferDto t = transferService.transferExternal(external(a, "0123456701", "1000.00", key()));
        accountService.changeStatus(a.wallet(), AccountStatus.FROZEN, "investigation", admin().customerId());

        payoutProcessor.applyGatewayResult(t.reference(), PayoutStatus.FAILED, null, "Bank down");

        assertThat(balance(a.wallet())).isEqualByComparingTo("5000.00");
    }

    // ---------------------------------------------------------------- external + fees

    @Test
    void externalPayoutHoldsAmountPlusFeeThenSettles() {
        TestCustomer a = customer("5000.00");
        BigDecimal clearingBefore = balance(InternalAccounts.NGN_PAYOUT_CLEARING);
        BigDecimal feesBefore = balance(InternalAccounts.NGN_FEE_INCOME);

        TransferDto t = transferService.transferExternal(external(a, "0123456789", "1500.00", key()));

        assertThat(t.status()).isEqualTo("PROCESSING");
        assertThat(t.fee()).isEqualByComparingTo("10.00");
        assertThat(t.totalDebit()).isEqualByComparingTo("1510.00");
        assertThat(t.beneficiaryAccountNumber()).isEqualTo("******6789");
        assertThat(balance(a.wallet())).isEqualByComparingTo("3490.00");
        assertThat(balance(InternalAccounts.NGN_FEE_INCOME).subtract(feesBefore)).isEqualByComparingTo("10.00");

        payoutProcessor.applyGatewayResult(t.reference(), PayoutStatus.SUCCESS, null, "ok");

        TransferDto done = transferService.getByReference(t.reference());
        assertThat(done.status()).isEqualTo("SUCCESS");
        assertThat(balance(InternalAccounts.NGN_PAYOUT_CLEARING)).isEqualByComparingTo(clearingBefore);
    }

    @Test
    void feeBandsApply() {
        assertThat(transferService.quote(TransferType.EXTERNAL, new BigDecimal("5000.00")).fee()).isEqualByComparingTo("10.00");
        assertThat(transferService.quote(TransferType.EXTERNAL, new BigDecimal("5000.01")).fee()).isEqualByComparingTo("25.00");
        assertThat(transferService.quote(TransferType.EXTERNAL, new BigDecimal("50000.01")).fee()).isEqualByComparingTo("50.00");
        assertThat(transferService.quote(TransferType.INTERNAL, new BigDecimal("50000.01")).fee()).isEqualByComparingTo("0.00");
    }

    @Test
    void feeMustBeCoveredByBalance() {
        TestCustomer a = customer("1005.00");
        assertStatus(() -> transferService.transferExternal(external(a, "0123456702", "1000.00", key())),
                HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(balance(a.wallet())).isEqualByComparingTo("1005.00");
    }

    @Test
    void rejectedPayoutRefundsAmountAndFee() {
        TestCustomer a = customer("5000.00");
        BigDecimal feesBefore = balance(InternalAccounts.NGN_FEE_INCOME);

        TransferDto t = transferService.transferExternal(external(a, "8888888888", "1000.00", key()));

        assertThat(t.status()).isEqualTo("FAILED");
        assertThat(balance(a.wallet())).isEqualByComparingTo("5000.00");
        assertThat(balance(InternalAccounts.NGN_FEE_INCOME)).isEqualByComparingTo(feesBefore);
    }

    @Test
    void failedNameEnquiryMovesNoMoney() {
        TestCustomer a = customer("5000.00");
        assertThatThrownBy(() -> transferService.transferExternal(external(a, "9999999999", "1000.00", key())))
                .isInstanceOf(PayBridgeException.class);
        assertThat(balance(a.wallet())).isEqualByComparingTo("5000.00");
    }

    @Test
    void unknownOutcomeKeepsFundsHeldUntilReconciled() {
        TestCustomer a = customer("5000.00");
        TransferDto t = transferService.transferExternal(external(a, "7777777777", "1000.00", key()));
        assertThat(t.status()).isEqualTo("PENDING");
        assertThat(balance(a.wallet())).isEqualByComparingTo("3990.00");
    }

    @Test
    void reconcilerCompletesPayoutsWhoseWebhookWasLost() {
        TestCustomer a = customer("5000.00");
        TransferDto t = transferService.transferExternal(external(a, "0123456780", "700.00", key()));

        payoutProcessor.reconcileStalePayouts(Duration.ZERO, 1000);

        assertThat(transferService.getByReference(t.reference()).status()).isEqualTo("SUCCESS");
    }

    @Test
    void duplicateAndOutOfOrderWebhooksRefundExactlyOnce() throws Exception {
        TestCustomer a = customer("5000.00");
        TransferDto t = transferService.transferExternal(external(a, "0123456781", "2000.00", key()));

        runConcurrently(8, i -> payoutProcessor.applyGatewayResult(t.reference(), PayoutStatus.FAILED, null, "down"));
        payoutProcessor.applyGatewayResult(t.reference(), PayoutStatus.SUCCESS, null, "late");

        assertThat(transferService.getByReference(t.reference()).status()).isEqualTo("FAILED");
        assertThat(balance(a.wallet())).isEqualByComparingTo("5000.00");
    }

    @Test
    void reversalAfterSuccessRefundsAmountAndFee() {
        TestCustomer a = customer("5000.00");
        TransferDto t = transferService.transferExternal(external(a, "0123456783", "1200.00", key()));
        payoutProcessor.applyGatewayResult(t.reference(), PayoutStatus.SUCCESS, null, "ok");

        payoutProcessor.applyGatewayResult(t.reference(), PayoutStatus.REVERSED, null, "Beneficiary account closed");
        payoutProcessor.applyGatewayResult(t.reference(), PayoutStatus.REVERSED, null, "duplicate");

        assertThat(transferService.getByReference(t.reference()).status()).isEqualTo("REVERSED");
        assertThat(balance(a.wallet())).isEqualByComparingTo("5000.00");
    }

    // ---------------------------------------------------------------- beneficiaries

    @Test
    void savedExternalBeneficiarySkipsDetailsAndIsPrivate() {
        TestCustomer a = customer("5000.00");
        TestCustomer other = customer("5000.00");
        BeneficiaryService.BeneficiaryDto saved = beneficiaryService.addExternal(a.id(), "0123456790", BANK, "Landlord");

        TransferDto t = transferService.transferExternal(new ExternalTransferCommand(a.id(), a.wallet(), null, null,
                saved.id(), new BigDecimal("1000.00"), "rent", key(), false));
        assertThat(t.beneficiaryName()).isEqualTo("SIMULATED BENEFICIARY 6790");

        assertStatus(() -> transferService.transferExternal(new ExternalTransferCommand(other.id(), other.wallet(),
                null, null, saved.id(), new BigDecimal("1000.00"), "steal", key(), false)), HttpStatus.NOT_FOUND);
    }

    @Test
    void saveBeneficiaryFlagRemembersRecipientOnce() {
        TestCustomer a = customer("5000.00");
        TestCustomer b = customer();
        transferService.transferInternal(new InternalTransferCommand(a.id(), a.wallet(), b.wallet(), null,
                new BigDecimal("10.00"), "lunch", key(), true));
        transferService.transferInternal(new InternalTransferCommand(a.id(), a.wallet(), b.wallet(), null,
                new BigDecimal("10.00"), "lunch again", key(), true));

        List<BeneficiaryService.BeneficiaryDto> list = beneficiaryService.list(a.id());
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().walletNumber()).isEqualTo(b.wallet());
    }

    // ---------------------------------------------------------------- helpers

    private static InternalTransferCommand internal(TestCustomer from, String toWallet, String amount, String key) {
        return new InternalTransferCommand(from.id(), from.wallet(), toWallet, null, new BigDecimal(amount),
                "test transfer", key, false);
    }

    private static ExternalTransferCommand external(TestCustomer from, String account, String amount, String key) {
        return new ExternalTransferCommand(from.id(), from.wallet(), account, BANK, null, new BigDecimal(amount),
                "test payout", key, false);
    }

    private static void assertStatus(ThrowableAssert.ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call)
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getStatus()).isEqualTo(expected);
    }

    @FunctionalInterface
    interface IndexedTask { void run(int i) throws Exception; }

    private static void runConcurrently(int threads, IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            Callable<Void> c = () -> { start.await(); task.run(index); return null; };
            futures.add(pool.submit(c));
        }
        start.countDown();
        for (Future<Void> f : futures) {
            f.get(30, TimeUnit.SECONDS); // rethrows any unexpected failure (e.g. a deadlock)
        }
        pool.shutdown();
    }
}

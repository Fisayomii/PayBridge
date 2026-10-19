package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.InternalAccounts;
import com.academy.paybridge.account.api.MovementKind;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.settlement.domain.FxDeal;
import com.academy.paybridge.settlement.domain.PartnerPrefund;
import com.academy.paybridge.settlement.repository.FxDealRepository;
import com.academy.paybridge.settlement.repository.PartnerPrefundRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The real-money side of remittance: partner wires arriving at our USD bank account, and the daily sale of
 * the USD we bought from partners for the NGN we paid out.
 *
 * <pre>
 *   prefund   GL-USD-NOSTRO       -> PARTNER-USD          [pb-pre-..]
 *   FX deal   GL-USD-FX-POSITION  -> GL-USD-NOSTRO        [pb-fxd-..-USD]  whole open USD position sold
 *             GL-NGN-NOSTRO       -> GL-NGN-FX-POSITION   [pb-fxd-..-NGN]  NGN the bank paid us
 *             GL-NGN-FX-POSITION <-> GL-NGN-FX-PNL        [pb-fxd-..-PNL]  what is left is the realised gain/loss
 * </pre>
 * After a deal both FX position accounts are back at exactly zero.
 */
@Service
public class TreasuryService {

    private static final Logger log = LogManager.getLogger(TreasuryService.class);

    private final AccountApi accountApi;
    private final LedgerApi ledgerApi;
    private final FxApi fxApi;
    private final PartnerPrefundRepository prefundRepository;
    private final FxDealRepository dealRepository;

    public TreasuryService(AccountApi accountApi, LedgerApi ledgerApi, FxApi fxApi,
                           PartnerPrefundRepository prefundRepository, FxDealRepository dealRepository) {
        this.accountApi = accountApi;
        this.ledgerApi = ledgerApi;
        this.fxApi = fxApi;
        this.prefundRepository = prefundRepository;
        this.dealRepository = dealRepository;
    }

    // ------------------------------------------------------------------ prefunds

    /**
     * Books a partner's wire once it is visible on our USD bank statement. The bank's reference makes it
     * idempotent: the same wire can never be credited twice.
     */
    @Transactional
    public PrefundView recordPrefund(String partnerAccountNumber, BigDecimal amount, String bankReference, UUID adminId) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw PayBridgeException.badRequest("Amount must be positive with at most 2 decimal places");
        }
        AccountDto partner = accountApi.getByWalletNumber(partnerAccountNumber);
        if (partner.type() != AccountType.PARTNER) {
            throw PayBridgeException.notFound("Partner account not found: " + partnerAccountNumber);
        }
        String bankRef = bankReference.trim();
        var existing = prefundRepository.findByBankReference(bankRef);
        if (existing.isPresent()) {
            PartnerPrefund p = existing.get();
            if (!p.getPartnerAccountId().equals(partner.id()) || p.getAmount().compareTo(amount) != 0) {
                throw PayBridgeException.conflict("Bank reference " + bankRef + " was already booked for a different wire");
            }
            return view(p, partner.walletNumber(), accountApi.getById(partner.id()).balance());
        }
        UUID nostro = gl(InternalAccounts.USD_NOSTRO);
        String reference = "pb-pre-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        PartnerPrefund p = prefundRepository.saveAndFlush(new PartnerPrefund(reference, partner.id(),
                amount.setScale(2, RoundingMode.UNNECESSARY), partner.currency(), bankRef, adminId));
        post(reference, nostro, partner.id(), p.getAmount(), partner.currency(),
                "Prefund received, bank ref " + bankRef);
        log.info("AUDIT prefund {} {} {} for {} by {} (bank ref {})", reference, partner.currency(), amount,
                partnerAccountNumber, adminId, bankRef);
        return view(p, partner.walletNumber(), accountApi.getById(partner.id()).balance());
    }

    @Transactional(readOnly = true)
    public List<PrefundView> listPrefunds() {
        return prefundRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(p -> view(p, accountApi.getById(p.getPartnerAccountId()).walletNumber(), null))
                .toList();
    }

    public record PrefundView(String reference, String partnerAccountNumber, BigDecimal amount, String currency,
                              String bankReference, BigDecimal partnerBalanceAfter, java.time.Instant createdAt) {}

    private PrefundView view(PartnerPrefund p, String accountNumber, BigDecimal balance) {
        return new PrefundView(p.getReference(), accountNumber, p.getAmount(), p.getCurrency(), p.getBankReference(),
                balance, p.getCreatedAt());
    }

    // ------------------------------------------------------------------ FX position and deals

    /** The open position: USD we own but have not sold, against the NGN we already paid out for it. */
    @Transactional(readOnly = true)
    public FxPosition position() {
        BigDecimal usdOpen = accountApi.getByWalletNumber(InternalAccounts.USD_FX_POSITION).balance();
        BigDecimal ngnPaidOut = accountApi.getByWalletNumber(InternalAccounts.NGN_FX_POSITION).balance().negate();
        BigDecimal mid = fxApi.currentMidRate();
        BigDecimal averageRate = usdOpen.signum() == 0 ? null
                : ngnPaidOut.divide(usdOpen, 8, RoundingMode.HALF_EVEN);
        BigDecimal markToMarket = usdOpen.multiply(mid).setScale(2, RoundingMode.HALF_EVEN).subtract(ngnPaidOut);
        BigDecimal realisedToDate = accountApi.getByWalletNumber(InternalAccounts.NGN_FX_PNL).balance();
        return new FxPosition(usdOpen, ngnPaidOut, averageRate, mid, markToMarket, realisedToDate);
    }

    /**
     * @param averagePaidOutRate NGN per USD we paid recipients on average
     * @param unrealisedPnl      gain (positive) or loss if the position were sold now at the mid rate
     * @param realisedPnlToDate  balance of GL-NGN-FX-PNL
     */
    public record FxPosition(BigDecimal usdOpen, BigDecimal ngnPaidOut, BigDecimal averagePaidOutRate,
                             BigDecimal currentMidRate, BigDecimal unrealisedPnl, BigDecimal realisedPnlToDate) {}

    /**
     * Books the sale of the whole open USD position at the rate the dealer bank executed. Selling everything
     * at once keeps the gain/loss exact: both FX position accounts end at zero and the difference lands in P&L.
     */
    @Transactional
    public FxDeal bookFxDeal(BigDecimal executedRate, String bankReference, UUID adminId) {
        if (executedRate == null || executedRate.signum() <= 0) {
            throw PayBridgeException.badRequest("Executed rate must be positive");
        }
        String bankRef = bankReference.trim();
        if (dealRepository.findByBankReference(bankRef).isPresent()) {
            throw PayBridgeException.conflict("FX deal with bank reference " + bankRef + " is already booked");
        }
        UUID usdPosition = gl(InternalAccounts.USD_FX_POSITION);
        UUID usdNostro = gl(InternalAccounts.USD_NOSTRO);
        UUID ngnPosition = gl(InternalAccounts.NGN_FX_POSITION);
        UUID ngnNostro = gl(InternalAccounts.NGN_NOSTRO);
        UUID pnl = gl(InternalAccounts.NGN_FX_PNL);
        Map<UUID, AccountDto> locked = accountApi.lockForUpdate(List.of(usdPosition, usdNostro, ngnPosition, ngnNostro, pnl));

        BigDecimal usd = locked.get(usdPosition).balance();
        if (usd.signum() <= 0) {
            throw PayBridgeException.unprocessable("There is no open USD position to sell");
        }
        BigDecimal ngnPaidOut = locked.get(ngnPosition).balance().negate();
        BigDecimal ngnReceived = usd.multiply(executedRate).setScale(2, RoundingMode.HALF_EVEN);
        String reference = "pb-fxd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        FxDeal deal = dealRepository.saveAndFlush(new FxDeal(reference, bankRef, usd,
                executedRate.setScale(8, RoundingMode.HALF_EVEN), ngnReceived, ngnPaidOut, adminId));

        post(reference + "-USD", usdPosition, usdNostro, usd, "USD", "FX deal: USD sold, bank ref " + bankRef);
        post(reference + "-NGN", ngnNostro, ngnPosition, ngnReceived, "NGN", "FX deal: NGN bought, bank ref " + bankRef);
        BigDecimal result = deal.getRealisedPnl();
        if (result.signum() > 0) {
            post(reference + "-PNL", ngnPosition, pnl, result, "NGN", "FX gain on deal " + reference);
        } else if (result.signum() < 0) {
            post(reference + "-PNL", pnl, ngnPosition, result.negate(), "NGN", "FX loss on deal " + reference);
        }
        log.info("AUDIT FX deal {}: sold USD {} at {} for NGN {} (paid out NGN {}), P&L NGN {} by {}",
                reference, usd, executedRate, ngnReceived, ngnPaidOut, result, adminId);
        return deal;
    }

    @Transactional(readOnly = true)
    public List<FxDeal> listDeals() {
        return dealRepository.findAllByOrderByCreatedAtDesc();
    }

    private void post(String reference, UUID from, UUID to, BigDecimal amount, String currency, String narration) {
        accountApi.moveFunds(from, to, amount, currency, MovementKind.SYSTEM);
        ledgerApi.recordDoubleEntry(reference, from, to, amount, currency, narration);
    }

    private UUID gl(String walletNumber) {
        return accountApi.getByWalletNumber(walletNumber).id();
    }
}

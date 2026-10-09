package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryDto;
import com.academy.paybridge.notification.api.TransferEvent;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.api.TransferDto;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final AccountApi accountApi;
    private final LedgerApi ledgerApi;
    private final ComplianceApi complianceApi;
    private final TransferGateway transferGateway;
    private final ApplicationEventPublisher eventPublisher;

    public TransferService(TransferRepository transferRepository,
                           AccountApi accountApi,
                           LedgerApi ledgerApi,
                           ComplianceApi complianceApi,
                           TransferGateway transferGateway,
                           ApplicationEventPublisher eventPublisher) {
        this.transferRepository = transferRepository;
        this.accountApi = accountApi;
        this.ledgerApi = ledgerApi;
        this.complianceApi = complianceApi;
        this.transferGateway = transferGateway;
        this.eventPublisher = eventPublisher;
    }

    public TransferGateway.BankResolutionResult resolveBankAccount(String accountNumber, String bankCode) {
        return transferGateway.resolveBankAccount(accountNumber, bankCode);
    }

    @Transactional
    public TransferDto transferBetweenWallets(String sourceWalletNumber,
                                              String destinationWalletNumber,
                                              BigDecimal amount,
                                              String narration,
                                              String idempotencyKey) {
        // 1. Idempotency Check (never charge twice on network retry)
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return buildTransferResponse(existing.get());
        }

        if (sourceWalletNumber.equalsIgnoreCase(destinationWalletNumber)) {
            throw PayBridgeException.badRequest("Source and destination wallets cannot be the same");
        }

        // 2. AML & CBN Transaction Limit Screening via compliance.api
        complianceApi.validateTransfer(sourceWalletNumber, destinationWalletNumber, amount, narration);

        // 3. Fetch Wallets via account.api (Strict Modular Boundary)
        AccountDto sourceWallet = accountApi.getByWalletNumber(sourceWalletNumber);
        AccountDto destinationWallet = accountApi.getByWalletNumber(destinationWalletNumber);

        if (sourceWallet.balance().compareTo(amount) < 0) {
            throw PayBridgeException.badRequest("Insufficient funds in " + sourceWalletNumber
                    + ". Available: NGN " + sourceWallet.balance());
        }

        // 4. Connect to Paystack Sandbox: Name Enquiry -> Create Recipient -> Initiate Transfer
        TransferGateway.BankResolutionResult resolution = transferGateway.resolveBankAccount(
                destinationWallet.externalBankAccountNumber(),
                destinationWallet.externalBankCode()
        );

        String recipientCode = transferGateway.createTransferRecipient(
                resolution.accountName(),
                destinationWallet.externalBankAccountNumber(),
                destinationWallet.externalBankCode()
        );

        String reference = "PB-TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        TransferGateway.GatewayPayoutResult gatewayResult = transferGateway.initiateTransfer(
                recipientCode,
                amount,
                reference,
                narration
        );

        // 5. Debit Sender Wallet & Credit Receiver Wallet via account.api
        accountApi.debit(sourceWallet.id(), amount, "NGN");
        accountApi.credit(destinationWallet.id(), amount, "NGN");

        // 6. Record Double-Entry Bookkeeping via ledger.api
        ledgerApi.recordDoubleEntry(
                reference,
                sourceWallet.id(),
                destinationWallet.id(),
                amount,
                "NGN",
                narration
        );

        // 7. Persist Transfer Receipt
        Transfer transfer = new Transfer(
                reference,
                idempotencyKey,
                sourceWallet.id(),
                sourceWallet.walletNumber(),
                destinationWallet.id(),
                destinationWallet.walletNumber(),
                resolution.accountName(),
                recipientCode,
                gatewayResult.gatewayReference(),
                amount,
                "NGN",
                TransferStatus.SUCCESS,
                narration
        );
        Transfer saved = transferRepository.save(transfer);
        TransferDto response = buildTransferResponse(saved);

        // 8. Publish Event for Notification Module (Decoupled Event-Driven Alert)
        eventPublisher.publishEvent(new TransferEvent(
                response.reference(),
                response.sourceWalletNumber(),
                sourceWallet.email(),
                response.destinationWalletNumber(),
                destinationWallet.email(),
                response.resolvedBeneficiaryName(),
                response.amount(),
                response.currency(),
                response.sourceWalletBalanceAfter(),
                response.destinationWalletBalanceAfter(),
                response.status(),
                response.narration()
        ));

        return response;
    }

    @Transactional(readOnly = true)
    public TransferDto getByReference(String reference) {
        Transfer transfer = transferRepository.findByReference(reference)
                .orElseThrow(() -> PayBridgeException.notFound("Transfer not found: " + reference));
        return buildTransferResponse(transfer);
    }

    @Transactional
    public TransferDto handleWebhookUpdate(String reference, String eventType) {
        Transfer transfer = transferRepository.findByReference(reference)
                .orElseThrow(() -> PayBridgeException.notFound("Transfer not found for webhook: " + reference));

        if ("transfer.failed".equalsIgnoreCase(eventType) && transfer.getStatus() != TransferStatus.REVERSED) {
            accountApi.debit(transfer.getDestinationAccountId(), transfer.getAmount(), "NGN");
            accountApi.credit(transfer.getSourceAccountId(), transfer.getAmount(), "NGN");
            ledgerApi.recordDoubleEntry(
                    transfer.getReference() + "-REV",
                    transfer.getDestinationAccountId(),
                    transfer.getSourceAccountId(),
                    transfer.getAmount(),
                    "NGN",
                    "REVERSAL: " + transfer.getNarration()
            );
            transfer.markStatus(TransferStatus.REVERSED);
        } else if ("transfer.success".equalsIgnoreCase(eventType)) {
            transfer.markStatus(TransferStatus.SUCCESS);
        }
        AccountDto sourceWallet = accountApi.getById(transfer.getSourceAccountId());
        AccountDto destinationWallet = accountApi.getById(transfer.getDestinationAccountId());

        TransferDto response = buildTransferResponse(transferRepository.save(transfer));
        eventPublisher.publishEvent(new TransferEvent(
                response.reference(),
                response.sourceWalletNumber(),
                sourceWallet.email(),
                response.destinationWalletNumber(),
                destinationWallet.email(),
                response.resolvedBeneficiaryName(),
                response.amount(),
                response.currency(),
                response.sourceWalletBalanceAfter(),
                response.destinationWalletBalanceAfter(),
                response.status(),
                "WEBHOOK UPDATE: " + eventType
        ));
        return response;
    }

    private TransferDto buildTransferResponse(Transfer t) {
        AccountDto updatedSource = accountApi.getById(t.getSourceAccountId());
        AccountDto updatedDest = accountApi.getById(t.getDestinationAccountId());
        List<LedgerEntryDto> ledgerEntries = ledgerApi.getEntriesByReference(t.getReference());

        return new TransferDto(
                t.getId(),
                t.getReference(),
                t.getIdempotencyKey(),
                t.getSourceWalletNumber(),
                t.getDestinationWalletNumber(),
                t.getResolvedBeneficiaryName(),
                t.getRecipientCode(),
                t.getGatewayTransferCode(),
                t.getAmount(),
                t.getCurrency(),
                t.getStatus().name(),
                t.getNarration(),
                updatedSource.balance(),
                updatedDest.balance(),
                ledgerEntries,
                t.getCreatedAt()
        );
    }
}
package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountDto;
import com.academy.paybridge.notification.api.TransferEvent;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
class TransferEventPublisher {

    private final AccountApi accountApi;
    private final ApplicationEventPublisher eventPublisher;

    TransferEventPublisher(AccountApi accountApi, ApplicationEventPublisher eventPublisher) {
        this.accountApi = accountApi;
        this.eventPublisher = eventPublisher;
    }

    void publish(Transfer t) {
        AccountDto source = accountApi.getById(t.getSourceAccountId());
        boolean internal = t.getType() == TransferType.INTERNAL;
        AccountDto destination = internal ? accountApi.getById(t.getDestinationAccountId()) : null;

        eventPublisher.publishEvent(new TransferEvent(
                t.getReference(),
                t.getType().name(),
                t.getSourceWalletNumber(),
                source.email(),
                internal ? t.getDestinationWalletNumber() : maskAccountNumber(t.getBeneficiaryAccountNumber()),
                internal ? destination.email() : null,
                t.getResolvedBeneficiaryName(),
                t.getAmount(),
                t.getCurrency(),
                source.balance(),
                internal ? destination.balance() : null,
                t.getStatus().name(),
                t.getNarration()
        ));
    }

    static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) return accountNumber;
        return "******" + accountNumber.substring(accountNumber.length() - 4);
    }
}

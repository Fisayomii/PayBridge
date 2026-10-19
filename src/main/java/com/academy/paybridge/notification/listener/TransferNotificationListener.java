package com.academy.paybridge.notification.listener;

import com.academy.paybridge.notification.api.AccountCreatedEvent;
import com.academy.paybridge.notification.api.FundingEvent;
import com.academy.paybridge.notification.api.RemittanceEvent;
import com.academy.paybridge.notification.api.TransferEvent;
import com.academy.paybridge.notification.service.NotificationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransferNotificationListener {

    private final NotificationService notificationService;

    public TransferNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Only alert once the money movement is committed; fallbackExecution covers events published outside a transaction. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransferEvent(TransferEvent event) {
        notificationService.sendTransferAlerts(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountCreated(AccountCreatedEvent event) {
        notificationService.sendWelcomeEmail(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFunding(FundingEvent event) {
        notificationService.sendFundingAlert(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRemittance(RemittanceEvent event) {
        notificationService.sendRemittanceAlert(event);
    }
}

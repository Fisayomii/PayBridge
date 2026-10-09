package com.academy.paybridge.notification.listener;

import com.academy.paybridge.notification.api.TransferEvent;
import com.academy.paybridge.notification.service.NotificationService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TransferNotificationListener {

    private final NotificationService notificationService;

    public TransferNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void onTransferEvent(TransferEvent event) {
        notificationService.sendTransferAlerts(event);
    }
    @org.springframework.context.event.EventListener
    public void onAccountCreated(com.academy.paybridge.notification.api.AccountCreatedEvent event) {
        notificationService.sendWelcomeEmail(event);
    }

}
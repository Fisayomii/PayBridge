package com.academy.paybridge.notification.api;

public record AccountCreatedEvent(
        String walletNumber,
        String ownerName,
        String email
) {}

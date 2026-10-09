package com.academy.paybridge.transfer.client;

public record PaystackResolveResponse(
        boolean status,
        String message,
        Data data
) {
    public record Data(
            String account_number,
            String account_name,
            Integer bank_id
    ) {}
}

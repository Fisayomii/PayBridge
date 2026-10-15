package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.paystack.PaystackWebhookHandler;
import com.academy.paybridge.transfer.client.TransferGateway.PayoutStatus;
import com.academy.paybridge.transfer.service.PayoutProcessor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** transfer.success / transfer.failed / transfer.reversed -> payout state machine. */
@Component
public class PaystackTransferWebhookHandler implements PaystackWebhookHandler {

    private final PayoutProcessor payoutProcessor;

    public PaystackTransferWebhookHandler(PayoutProcessor payoutProcessor) {
        this.payoutProcessor = payoutProcessor;
    }

    @Override
    public boolean handles(String event) {
        return event.startsWith("transfer.");
    }

    @Override
    public void handle(String event, JsonNode data) {
        PayoutStatus status = switch (event) {
            case "transfer.success" -> PayoutStatus.SUCCESS;
            case "transfer.failed" -> PayoutStatus.FAILED;
            case "transfer.reversed" -> PayoutStatus.REVERSED;
            default -> null;
        };
        String reference = data.path("reference").asString(null);
        if (status == null || reference == null) {
            return;
        }
        payoutProcessor.applyGatewayResult(reference, status,
                data.path("transfer_code").asString(null), data.path("reason").asString(null));
    }
}

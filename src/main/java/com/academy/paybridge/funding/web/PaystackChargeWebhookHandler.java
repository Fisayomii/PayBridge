package com.academy.paybridge.funding.web;

import com.academy.paybridge.funding.service.FundingService;
import com.academy.paybridge.shared.paystack.PaystackWebhookHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * charge.success -> verify with Paystack and credit the wallet. The webhook only triggers verification;
 * the amount credited always comes from Paystack's verify endpoint, never from the webhook body.
 */
@Component
public class PaystackChargeWebhookHandler implements PaystackWebhookHandler {

    private final FundingService fundingService;

    public PaystackChargeWebhookHandler(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @Override
    public boolean handles(String event) {
        return "charge.success".equals(event);
    }

    @Override
    public void handle(String event, JsonNode data) {
        String reference = data.path("reference").asString(null);
        if (reference != null) {
            fundingService.confirmCheckout(reference);
        }
    }
}

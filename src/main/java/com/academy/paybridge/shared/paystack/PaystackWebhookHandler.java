package com.academy.paybridge.shared.paystack;

import tools.jackson.databind.JsonNode;

/**
 * Paystack delivers every event type to a single URL. Each module that cares about some events
 * (transfer: transfer.*, funding: charge.success) registers a handler; the shared webhook controller
 * verifies the signature once and dispatches.
 *
 * <p>Handlers must be idempotent (Paystack retries) and should throw a NOT_FOUND
 * {@link com.academy.paybridge.shared.exception.PayBridgeException} for references they do not own.
 * Any other exception makes the webhook return 5xx so Paystack redelivers.
 */
public interface PaystackWebhookHandler {

    boolean handles(String event);

    void handle(String event, JsonNode data);
}

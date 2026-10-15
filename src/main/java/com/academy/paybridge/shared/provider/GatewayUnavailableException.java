package com.academy.paybridge.shared.provider;

import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.http.HttpStatus;

/** Timeout, connection error or provider 5xx. The outcome of the request is unknown. */
public class GatewayUnavailableException extends PayBridgeException {
    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}

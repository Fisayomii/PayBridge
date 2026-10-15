package com.academy.paybridge.shared.provider;

import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.http.HttpStatus;

/** The provider definitively refused the request. Safe to treat as "nothing was sent". */
public class GatewayRejectedException extends PayBridgeException {
    public GatewayRejectedException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_CONTENT);
    }
}

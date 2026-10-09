package com.academy.paybridge.shared.exception;

import org.springframework.http.HttpStatus;

public class PayBridgeException extends RuntimeException {
    private final HttpStatus status;

    public PayBridgeException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public static PayBridgeException badRequest(String message) {
        return new PayBridgeException(message, HttpStatus.BAD_REQUEST);
    }

    public static PayBridgeException notFound(String message) {
        return new PayBridgeException(message, HttpStatus.NOT_FOUND);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
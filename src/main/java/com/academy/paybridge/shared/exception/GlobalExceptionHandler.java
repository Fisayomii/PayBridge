package com.academy.paybridge.shared.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PayBridgeException.class)
    public ResponseEntity<Map<String, Object>> handleDomainException(PayBridgeException ex) {
        return body(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return body(HttpStatus.BAD_REQUEST, errors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return body(HttpStatus.BAD_REQUEST, "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleCircuitOpen(CallNotPermittedException ex) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider is temporarily unavailable. Please retry shortly.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "The resource was modified concurrently. Please retry.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        return body(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /** Last resort: never leak stack traces or SQL to clients, but keep Spring's own 4xx statuses intact. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            return body(status, errorResponse.getBody().getDetail() != null
                    ? errorResponse.getBody().getDetail() : ex.getMessage());
        }
        log.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", message == null ? "" : message
        ));
    }
}

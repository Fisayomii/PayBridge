package com.academy.paybridge.shared.paystack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Paystack's standard envelope: {"status": true, "message": "...", "data": {...}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaystackResponse<T>(boolean status, String message, T data) {}

package com.agostinelli.gestionale.shared.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Risposta strutturata per tutti gli errori HTTP.
 * Il campo {@code details} porta errori per-campo da validation.
 */
public record ErrorResponse(
        String code,
        String message,
        Map<String, String> details,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Map.of(), Instant.now());
    }

    public static ErrorResponse of(String code, String message, Map<String, String> details) {
        return new ErrorResponse(code, message, details, Instant.now());
    }
}

package com.agostinelli.gestionale.shared.dto;

import java.time.Instant;

/**
 * Envelope standard per tutte le risposte REST dell'applicazione.
 * Garantisce formato uniforme tra successi ed errori parziali.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now());
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message, Instant.now());
    }
}

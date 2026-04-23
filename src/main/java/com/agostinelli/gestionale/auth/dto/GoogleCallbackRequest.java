package com.agostinelli.gestionale.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Parametri ricevuti da Google nel redirect OAuth2 callback.
 */
public record GoogleCallbackRequest(
    @NotBlank String code,
    @NotBlank String state
) {}

package com.agostinelli.gestionale.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Corpo della richiesta per il rinnovo dei token tramite refresh token.
 */
public record RefreshRequest(
    @NotBlank String refreshToken
) {}

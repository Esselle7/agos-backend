package com.agostinelli.gestionale.auth.dto;

import com.agostinelli.gestionale.auth.service.JwtService;

/**
 * Risposta al completamento del flusso OAuth2: contiene la coppia di token,
 * la durata di vita dell'access token in secondi e i dati utente.
 *
 * Il valore di {@code expiresIn} è derivato dalla costante di {@link JwtService}
 * per evitare disallineamenti silenziosi tra la TTL effettiva firmata nel JWT
 * e quella comunicata al frontend (che la usa per pianificare il refresh).
 */
public record LoginResponse(
    String accessToken,
    String refreshToken,
    int expiresIn,
    String tokenType,
    UserInfo user
) {
    public static LoginResponse of(String accessToken, String refreshToken, UserInfo user) {
        return new LoginResponse(accessToken, refreshToken, (int) JwtService.ACCESS_TTL_S, "Bearer", user);
    }
}

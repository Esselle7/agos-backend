package com.agostinelli.gestionale.auth.dto;

/**
 * Risposta al completamento del flusso OAuth2: contiene la coppia di token e i dati utente.
 */
public record LoginResponse(
    String accessToken,
    String refreshToken,
    int expiresIn,
    String tokenType,
    UserInfo user
) {
    public static LoginResponse of(String accessToken, String refreshToken, UserInfo user) {
        return new LoginResponse(accessToken, refreshToken, 3600, "Bearer", user);
    }
}

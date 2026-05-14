package com.agostinelli.gestionale.auth.dto;

/**
 * Risposta al refresh dei token: nuova coppia access/refresh token.
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    int expiresIn
) {
    public static TokenResponse of(String accessToken, String refreshToken) {
        return new TokenResponse(accessToken, refreshToken, 3600);
    }
}

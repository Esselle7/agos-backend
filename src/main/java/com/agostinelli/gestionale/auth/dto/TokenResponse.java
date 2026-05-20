package com.agostinelli.gestionale.auth.dto;

import com.agostinelli.gestionale.auth.service.JwtService;

/**
 * Risposta al refresh dei token: nuova coppia access/refresh token.
 *
 * Come {@link LoginResponse}, {@code expiresIn} è allineato alla costante
 * di {@link JwtService} per evitare drift tra TTL firmata e TTL comunicata.
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    int expiresIn
) {
    public static TokenResponse of(String accessToken, String refreshToken) {
        return new TokenResponse(accessToken, refreshToken, (int) JwtService.ACCESS_TTL_S);
    }
}

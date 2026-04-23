package com.agostinelli.gestionale.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Risposta dell'endpoint token di Google OAuth2 (https://oauth2.googleapis.com/token).
 */
public record GoogleTokenResponse(
    @JsonProperty("access_token")  String accessToken,
    @JsonProperty("id_token")      String idToken,
    @JsonProperty("token_type")    String tokenType,
    @JsonProperty("expires_in")    int expiresIn,
    @JsonProperty("refresh_token") String refreshToken,
    String scope
) {}

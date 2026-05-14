package com.agostinelli.gestionale.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Risposta JWKS di Google (https://www.googleapis.com/oauth2/v3/certs).
 * Contiene le chiavi pubbliche RSA usate per verificare i Google ID token.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleCertsResponse(List<JwkKey> keys) {

    /** Rappresentazione minima di una chiave JWK (kty=RSA). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JwkKey(String kty, String kid, String n, String e, String alg, String use) {}
}

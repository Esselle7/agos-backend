package com.agostinelli.gestionale.auth.service;

import java.util.UUID;

/**
 * Token JWT interno già validato e decodificato, pronto per l'uso nei servizi.
 */
public record DecodedToken(
    UUID userId,
    String email,
    String role,
    String jti,
    String type
) {}

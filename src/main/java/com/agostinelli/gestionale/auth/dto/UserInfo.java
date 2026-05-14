package com.agostinelli.gestionale.auth.dto;

import java.util.UUID;

/**
 * Informazioni essenziali sull'utente autenticato, incluse nel LoginResponse.
 */
public record UserInfo(
    UUID id,
    String email,
    String nome,
    String ruolo
) {}

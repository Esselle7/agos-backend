package com.agostinelli.gestionale.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body della richiesta {@code POST /auth/google/exchange}: il frontend scambia
 * il codice opaco ricevuto nel redirect OAuth con i veri JWT interni.
 */
public record ExchangeCodeRequest(
    @NotBlank(message = "code è obbligatorio")
    String code
) {}

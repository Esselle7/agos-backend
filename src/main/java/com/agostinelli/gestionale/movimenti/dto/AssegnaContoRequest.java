package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;

/** Attribuzione mirata di un movimento a un conto/cassa (popup "Senza banca"). */
public record AssegnaContoRequest(
        @NotNull Short contoBancarioId
) {}

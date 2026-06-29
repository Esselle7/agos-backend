package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotBlank;

/** Nuova categoria investimento (conto CAPEX) creata al volo dal form cespite. */
public record CategoriaCespiteRequest(
        @NotBlank String descrizione
) {}

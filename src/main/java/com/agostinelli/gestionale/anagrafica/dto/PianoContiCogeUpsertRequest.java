package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Richiesta di creazione/modifica di un conto del piano dei conti COGE.
 * Il {@code codice} è univoco ed è referenziato per stringa da keyword_firma e
 * regole_classificazione: il service ne propaga la modifica in transazione.
 */
public record PianoContiCogeUpsertRequest(
        @NotBlank @Size(max = 20) String codice,
        @NotBlank @Size(max = 255) String descrizione,
        @NotBlank String tipo,
        Integer parentId
) {}

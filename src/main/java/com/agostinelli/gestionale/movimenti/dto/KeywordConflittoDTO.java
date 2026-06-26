package com.agostinelli.gestionale.movimenti.dto;

import java.util.UUID;

/**
 * Voce della coda conflitti keyword per la pagina dedicata (tab Conflitti).
 * {@code targetEsistente}/{@code targetNuovo} sono JSON {bu,coge,fornitore} serializzati.
 */
public record KeywordConflittoDTO(
        UUID id,
        String tipo,             // APPRENDIMENTO | MATCH
        String stato,            // APERTO | RISOLTO | IGNORATO
        String signatureHash,
        UUID firmaEsistenteId,
        UUID movimentoId,
        String targetEsistente,
        String targetNuovo,
        String descrizione,
        String createdAt
) {}

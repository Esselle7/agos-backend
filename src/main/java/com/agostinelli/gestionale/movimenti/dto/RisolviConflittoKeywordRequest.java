package com.agostinelli.gestionale.movimenti.dto;

/**
 * Risoluzione a step di un conflitto keyword (PROMPT-KEYWORD-LEARNING.md §4.5):
 * TIENI_ESISTENTE (scarta il nuovo), USA_NUOVO (riporta la firma sul target nuovo),
 * SCARTA (disattiva la firma). UNISCI è trattato come TIENI_ESISTENTE (una firma per scope).
 */
public record RisolviConflittoKeywordRequest(
        String azione,   // TIENI_ESISTENTE | USA_NUOVO | UNISCI | SCARTA
        String note
) {}

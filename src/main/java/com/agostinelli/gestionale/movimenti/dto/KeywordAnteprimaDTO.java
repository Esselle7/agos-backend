package com.agostinelli.gestionale.movimenti.dto;

import java.util.List;

/**
 * Anteprima delle keyword che verrebbero apprese da una descrizione (PROMPT-KEYWORD-LEARNING.md §4.8):
 * mostrata nel triage/ambiguità prima di salvare, così l'utente vede e capisce cosa imparerà il
 * sistema. Riusa l'estrattore puro lato server (nessuna duplicazione di logica nel frontend).
 *
 * @param firme firme candidate (insieme di token + natura derivata)
 */
public record KeywordAnteprimaDTO(List<Firma> firme) {
    /** Una firma candidata: i token che la compongono (match in AND) e la natura. */
    public record Firma(List<String> token, String natura) {}
}

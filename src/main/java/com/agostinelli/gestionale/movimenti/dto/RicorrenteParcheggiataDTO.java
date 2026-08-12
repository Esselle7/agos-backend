package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Riga di spesa ricorrente / finanziamento parcheggiata dall'import (V9): NON è un movimento.
 * L'utente la riconcilia collegandola a un piano ricorrente, oppure la ignora.
 */
public record RicorrenteParcheggiataDTO(
        UUID id,
        String fonte,
        LocalDate dataMovimento,
        BigDecimal importo,
        String tipo,
        Short contoBancarioId,
        String descrizione,
        String tipoPresunto,    // MUTUO | FINANZIAMENTO | LEASING | CANONE | CAMBIALE | ASSICURAZIONE | BOLLO | RATA | ALTRO
        UUID recurringPlanId,
        String stato,           // DA_RICONCILIARE | CONFERMATA | IGNORATA | RICONCILIATA(legacy)
        // Suggerimento CoGe calcolato dal backend dalla descrizione (l'utente conferma o cambia).
        Integer cogeSuggeritoId,
        String cogeSuggeritoCodice,
        // Match strutturato coi piani ricorrenti attivi (SPEC ricorrenti-match-strutturato):
        // calcolato a ogni lettura, MAI persistito — è un suggerimento, non un'azione.
        // propostaRataId valorizzato = proposta a un click; null con candidati = sceglie l'utente.
        UUID propostaRataId,
        List<CandidatoRataDTO> candidati
) {
    /** Una rata compatibile con la riga, con il perché in chiaro. */
    public record CandidatoRataDTO(
            UUID pianoId,
            String pianoDescrizione,
            UUID rataId,
            int numeroRata,
            LocalDate dataScadenza,
            BigDecimal importoRata,
            long scartoGiorni,
            BigDecimal scartoImporto,
            String motivo
    ) {}
}

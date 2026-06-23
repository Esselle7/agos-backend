package com.agostinelli.gestionale.movimenti.dto;

import java.util.List;
import java.util.UUID;

/**
 * Risposta del flusso ETL (import file Billy / BPM / CA).
 * Distinta da {@link BulkImportResponse} (usato dal bulk JSON manuale) per non
 * rompere il contratto esistente.
 */
public record EtlImportResponse(
        UUID importLogId,
        int importati,
        int duplicati,
        int ambigui,
        int scartati,
        int parcheggiati,
        int ricorrenti,   // spese ricorrenti/finanziamenti parcheggiate (solo flusso congiunto)
        List<EtlRowError> errori,
        /**
         * Avvisi NON bloccanti (≠ errori): scontrini Billy elettronici non agganciati a un
         * accredito banca. Il {@code messaggio} è prefissato dalla natura dell'orfano:
         * {@code EVENTO_ATTESO:…} (incasso-evento agriturismo: il ricavo arriva dal bonifico
         * parcheggiato) oppure {@code SPACCIO_DA_VERIFICARE:…} (incasso spaccio non riconciliato).
         */
        List<EtlRowError> avvisi,
        /**
         * Feature 2 — Matching differiti: righe banca che combaciano (importo al centesimo +
         * descrizione uguale) con un movimento MANUALE DA_LIQUIDARE già presente in gestionale.
         * NON sono state persistite come nuovi movimenti (evita doppia registrazione): l'utente
         * le risolve dalla sezione "Matching differiti" dello smistamento (COLLEGA/IGNORA).
         */
        int matchingDifferiti
) {
    /** Costruttore di compatibilità per i chiamanti che non valorizzano il nuovo campo. */
    public EtlImportResponse(UUID importLogId, int importati, int duplicati, int ambigui,
                             int scartati, int parcheggiati, int ricorrenti,
                             List<EtlRowError> errori, List<EtlRowError> avvisi) {
        this(importLogId, importati, duplicati, ambigui, scartati, parcheggiati, ricorrenti,
                errori, avvisi, 0);
    }
}

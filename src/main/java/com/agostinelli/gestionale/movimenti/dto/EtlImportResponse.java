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
        List<EtlRowError> avvisi
) {}

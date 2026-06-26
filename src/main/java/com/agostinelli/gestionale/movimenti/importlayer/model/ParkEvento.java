package com.agostinelli.gestionale.movimenti.importlayer.model;

import java.time.LocalDate;

/**
 * Metadati di un movimento parcheggiato dal Gate B (ETL v2 §5) verso la coda
 * `eventi_da_riconciliare`. I dati anagrafici (chiave, importo, controparte, IBAN)
 * vengono letti dal {@link RawMovimento}; qui si conserva solo ciò che il gate
 * deduce dal testo.
 */
public record ParkEvento(
        String tipoEventoPresunto, // CAPARRA | ACCONTO | SALDO | AFFITTO_SALA | null
        String keywordMatch,       // keyword che ha attivato il parcheggio
        LocalDate dataEventoEstratta
) {}

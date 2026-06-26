package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;

/**
 * Regola data-driven del motore di classificazione (ETL v2 §9.1).
 * In create/update l'id è ignorato (assegnato dal DB).
 */
public record RegolaClassificazioneDTO(
        Integer id,
        int priorita,
        String sorgente,        // BILLY | CA | BPM | *
        String tipoMovimento,   // ENTRATA | USCITA | *
        String campo,           // CAUSALE | DESC_SPACED | DESC_COMPACT | IBAN
        String matchType,       // EQUALS | CONTAINS | STARTS_WITH | REGEX | IN_LIST
        String pattern,
        String azione,          // SKIP_POS | SKIP_GIROCONTO | SKIP_RICORRENTE | PARK_EVENTO | MAP
        String cogeCodice,
        Short buId,
        String metodoCodice,
        BigDecimal confidence,
        boolean attivo,
        String note
) {}

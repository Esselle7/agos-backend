package com.agostinelli.gestionale.movimenti.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ImportLogDTO(
        UUID id,
        String fonte,
        String filename,
        Instant dataImport,
        Integer righeTotali,
        Integer righeImportate,
        Integer righeErrore,
        Integer righeDuplicate,
        Integer righeAmbigue,
        Integer righeAmbigueClassificate,
        String stato,
        UUID importedBy,

        /**
         * Periodo di riferimento del file: min/max {@code data_movimento} dei movimenti che
         * citano questo import ({@code fonte_importazione_id}). NON e' la data di caricamento.
         *
         * <p>Si aggrega, non si denormalizza: e' lo stesso dato dei movimenti, e una colonna
         * in {@code import_log} sarebbe una seconda fonte di verita' che diverge appena una
         * riga ambigua viene classificata dopo (l'import del 19/08 ha 144 movimenti contro
         * {@code righe_importate = 81}).
         *
         * <p>{@code data_movimento} e non {@code data_finanziaria} perche' e' la data che il
         * parser legge dalla riga dell'estratto conto, e' NOT NULL, ed e' la chiave di
         * partizione: nessun flusso successivo puo' spostarla. Misurato l'08/09/2026 su 255
         * righe importate in produzione: 0 divergenze fra le due colonne, 0 NULL.
         *
         * <p>{@code null} quando l'import non ha prodotto movimenti (rollback, 0 righe a libro).
         */
        LocalDate periodoDal,
        LocalDate periodoAl
) {}

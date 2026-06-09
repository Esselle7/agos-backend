package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Classificazione manuale di un movimento su conto transitorio: lo sposta sul conto
 * COGE/BU corretti e, opzionalmente, apprende la controparte per IBAN così i prossimi
 * import la riconoscono da soli (ETL v2 §7.3).
 */
public record ClassificaTransitorioRequest(
        @NotNull Integer cogeId,
        @NotNull Short businessUnitId,
        UUID fornitoreId,
        boolean apprendiControparte,  // se true: upsert controparti by IBAN con coge/bu/fornitore scelti
        String nota
) {}

package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Classificazione manuale di un movimento su conto transitorio: lo sposta sul conto
 * COGE/BU corretti e, opzionalmente, apprende le KEYWORD dalla descrizione così i prossimi
 * import catalogano da soli una riga simile (PROMPT-KEYWORD-LEARNING.md §4.4).
 */
public record ClassificaTransitorioRequest(
        @NotNull Integer cogeId,
        @NotNull Short businessUnitId,
        UUID fornitoreId,
        boolean apprendiKeyword,  // se true: estrae firme IDENTITA dalla descrizione → target scelto
        String nota
) {}

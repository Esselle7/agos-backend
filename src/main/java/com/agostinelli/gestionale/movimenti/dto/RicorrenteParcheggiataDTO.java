package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        String stato            // DA_RICONCILIARE | RICONCILIATA | IGNORATA
) {}

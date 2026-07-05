package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * Creazione/aggiornamento di una voce di preventivo (riga quantità × prezzo unitario).
 *
 * Creazione: fornire {@code catalogoId} (voce di listino selezionata) OPPURE {@code label}
 * (voce nuova → registrata nel listino). Se {@code prezzoUnitario} è null e si seleziona una
 * voce di listino, il service usa il prezzo di default. {@code quantitaConsuntivo} è accettata
 * solo dalla data evento.
 *
 * Update (PATCH): i campi null sono ignorati.
 */
public record EventoVoceRequest(

        Long catalogoId,

        String label,

        @PositiveOrZero
        BigDecimal prezzoUnitario,

        @PositiveOrZero
        BigDecimal quantitaPreventivo,

        @PositiveOrZero
        BigDecimal quantitaConsuntivo,

        String note
) {}

package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Upsert di una voce di tracciamento del preventivato (AFFITTO o CATERING).
 * Nessun impatto contabile.
 */
public record EventoPreventivoTrackingRequest(

        /** AFFITTO | CATERING. */
        @NotBlank
        String tipo,

        /** Quota del preventivato attribuita all'affitto (tipo = AFFITTO). */
        BigDecimal importoIncasso,

        /** Costo interno per persona (tipo = CATERING). */
        BigDecimal costoPerPersona,

        /** Prezzo esposto al cliente per persona (tipo = CATERING). */
        BigDecimal prezzoPerPersona,

        /** N. persone (tipo = CATERING). Default = evento.numeroTotalePartecipanti. */
        Integer numPersone,

        String note
) {}

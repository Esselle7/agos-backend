package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;

/**
 * Voce di tracciamento del preventivato. I campi derivati del catering
 * (costoTotale, ricavo, margine, marginePerc) sono valorizzati solo per
 * il tipo CATERING; importoIncasso solo per AFFITTO.
 */
public record EventoPreventivoTrackingDTO(
        Long id,
        String tipo,
        // AFFITTO
        BigDecimal importoIncasso,
        // CATERING
        BigDecimal costoPerPersona,
        BigDecimal prezzoPerPersona,
        Integer numPersone,
        BigDecimal costoTotale,
        BigDecimal ricavo,
        BigDecimal margine,
        BigDecimal marginePerc,
        String note
) {}

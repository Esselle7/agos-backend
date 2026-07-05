package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;

/** Voce del listino riutilizzabile (per select/autocomplete in UI). */
public record EventoVoceCatalogoDTO(
        Long id,
        String label,
        boolean isDefault,
        BigDecimal prezzoDefault,
        String unita
) {}

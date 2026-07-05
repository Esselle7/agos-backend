package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;

/**
 * Vista di una voce di preventivo/consuntivo. Ogni riga è quantità × prezzo unitario.
 * {@code scostamento} = importoConsuntivo (se presente) − importoPreventivo.
 */
public record EventoVoceDTO(
        Long id,
        Long catalogoId,
        String label,
        BigDecimal prezzoUnitario,
        BigDecimal quantitaPreventivo,
        BigDecimal quantitaConsuntivo,
        BigDecimal importoPreventivo,
        BigDecimal importoConsuntivo,
        BigDecimal scostamento,
        String origine,
        Long costoDirettoId,
        String note
) {}

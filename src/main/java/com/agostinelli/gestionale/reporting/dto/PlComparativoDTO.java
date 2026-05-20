package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PlComparativoDTO(
        LocalDate from,
        LocalDate to,
        List<PlBuDTO> businessUnits,
        ConsolidatoDTO totaleConsolidato
) {
    public record PlBuDTO(
            BuRefDTO bu,
            BigDecimal ricavi,
            BigDecimal costi,
            BigDecimal ebitda,
            BigDecimal ebit,
            BigDecimal utileNetto,
            BigDecimal marginePct
    ) {}

    public record ConsolidatoDTO(
            BigDecimal ricavi,
            BigDecimal costi,
            BigDecimal ebitda,
            BigDecimal ammortamenti,
            BigDecimal ebit,
            BigDecimal oneriFinanziari,
            BigDecimal imposte,
            BigDecimal utileNetto,
            BigDecimal marginePct
    ) {}
}

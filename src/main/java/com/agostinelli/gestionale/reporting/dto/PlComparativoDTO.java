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
            BigDecimal marginePct
    ) {}

    public record ConsolidatoDTO(
            BigDecimal ricavi,
            BigDecimal costi,
            BigDecimal ebitda,
            BigDecimal marginePct
    ) {}
}

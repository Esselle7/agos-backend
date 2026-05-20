package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PlDTO(
        BuRefDTO bu,
        LocalDate from,
        LocalDate to,
        RicaviDTO ricavi,
        CostiDTO costi,
        BigDecimal ebitda,
        BigDecimal ammortamenti,
        BigDecimal ebit,
        BigDecimal oneriFinanziari,
        BigDecimal ebt,
        BigDecimal imposte,
        BigDecimal utileNetto,
        BigDecimal marginePct
) {
    public record RicaviDTO(BigDecimal totale, List<VoceDTO> perCategoria) {}

    public record CostiDTO(BigDecimal totale, BigDecimal capex, List<VoceDTO> perCategoria) {}
}

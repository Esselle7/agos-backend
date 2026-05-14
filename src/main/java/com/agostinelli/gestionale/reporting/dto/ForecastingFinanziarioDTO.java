package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.util.List;

public record ForecastingFinanziarioDTO(
        BigDecimal saldoPartenza,
        BigDecimal incassiPrevisti,
        BigDecimal uscitePreviste,
        BigDecimal saldoFinale,
        List<ForecastingTimelineDTO> timeline) {}

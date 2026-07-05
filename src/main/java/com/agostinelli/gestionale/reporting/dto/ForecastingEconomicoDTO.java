package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.util.List;

public record ForecastingEconomicoDTO(
        BigDecimal ricaviPrevisti,
        BigDecimal costiPrevisti,
        BigDecimal ebitdaPrevisto,
        /** Quota di ammortamento cespiti attesa nel periodo (tra EBITDA ed EBIT). */
        BigDecimal ammortamentiPrevisti,
        BigDecimal oneriFinanziariPrevisti,
        BigDecimal ebitPrevisto,
        List<ForecastingDettaglioDTO> dettaglio) {}

package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Punto della timeline finanziaria aggregata per bucket (settimana o mese).
 * entratePreviste = certo; entrateStimate = ricavi cash stimati (additivo, layer STIMATO).
 * saldoLiquiditaFine resta sul solo CERTO; il combinato lo deriva il frontend.
 */
public record ForecastingTimelineDTO(
        String bucket,
        LocalDate bucketStart,
        LocalDate bucketEnd,
        BigDecimal entratePreviste,
        BigDecimal uscitePreviste,
        BigDecimal ebitdaPeriodo,
        BigDecimal saldoLiquiditaFine,
        BigDecimal entrateStimate) {}

package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Punto della timeline finanziaria aggregata per bucket (settimana o mese).
 */
public record ForecastingTimelineDTO(
        String bucket,
        LocalDate bucketStart,
        LocalDate bucketEnd,
        BigDecimal entratePreviste,
        BigDecimal uscitePreviste,
        BigDecimal ebitdaPeriodo,
        BigDecimal saldoLiquiditaFine) {}

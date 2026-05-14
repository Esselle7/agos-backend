package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;

public record ForecastingAsIsDTO(
        BigDecimal saldoLiquidita,
        BigDecimal ricaviYtd,
        BigDecimal costiYtd,
        BigDecimal ebitdaYtd,
        BigDecimal creditiAperti,
        BigDecimal debitiAperti) {}

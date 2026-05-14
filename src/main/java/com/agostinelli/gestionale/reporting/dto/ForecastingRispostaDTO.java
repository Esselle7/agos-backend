package com.agostinelli.gestionale.reporting.dto;

public record ForecastingRispostaDTO(
        ForecastingAsIsDTO asIs,
        ForecastingEconomicoDTO economico,
        ForecastingFinanziarioDTO finanziario) {}

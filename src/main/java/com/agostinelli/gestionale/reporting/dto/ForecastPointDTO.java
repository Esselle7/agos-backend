package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ForecastPointDTO(
        LocalDate data,
        String tipo,
        BigDecimal entratePreviste,
        BigDecimal uscitePreviste,
        BigDecimal liquiditaProiettata,
        String note
) {}

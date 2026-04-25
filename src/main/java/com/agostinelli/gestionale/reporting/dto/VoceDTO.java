package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;

public record VoceDTO(
        String codiceCoge,
        String categoria,
        BigDecimal importo,
        BigDecimal pct
) {}

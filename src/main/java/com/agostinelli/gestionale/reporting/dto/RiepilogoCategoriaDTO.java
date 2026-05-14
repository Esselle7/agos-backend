package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;

public record RiepilogoCategoriaDTO(
        String categoria,
        long nMovimenti,
        BigDecimal totaleEntrate,
        BigDecimal totaleUscite
) {}

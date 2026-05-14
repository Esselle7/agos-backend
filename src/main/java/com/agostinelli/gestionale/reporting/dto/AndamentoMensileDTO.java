package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;

public record AndamentoMensileDTO(
        int anno,
        int mese,
        BigDecimal totEntrate,
        BigDecimal totUscite,
        BigDecimal margine
) {}

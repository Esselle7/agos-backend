package com.agostinelli.gestionale.spese.dto;

import java.math.BigDecimal;

public record LiquidatePlanRequest(
    BigDecimal importoTotale,  // null = usa somma residuo automatica
    String     note
) {}

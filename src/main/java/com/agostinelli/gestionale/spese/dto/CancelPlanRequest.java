package com.agostinelli.gestionale.spese.dto;

import java.math.BigDecimal;

public record CancelPlanRequest(
    BigDecimal importoPenale,  // null / 0 = nessuna penale
    String     note
) {}

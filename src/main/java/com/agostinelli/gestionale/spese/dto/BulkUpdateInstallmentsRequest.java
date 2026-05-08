package com.agostinelli.gestionale.spese.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record BulkUpdateInstallmentsRequest(
    @NotNull
    @DecimalMin("0.01")
    BigDecimal nuovoImporto
) {}

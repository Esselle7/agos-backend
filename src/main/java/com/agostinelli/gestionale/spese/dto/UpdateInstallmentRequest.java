package com.agostinelli.gestionale.spese.dto;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateInstallmentRequest(
    @DecimalMin("0.01")
    BigDecimal  importo,
    LocalDate   dataScadenza,
    String      note
) {}

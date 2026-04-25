package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashFlowPeriodoDTO(
        LocalDate periodoInizio,
        LocalDate periodoFine,
        BigDecimal entrate,
        BigDecimal uscite,
        BigDecimal saldoPeriodo,
        BigDecimal saldoCumulato
) {}

package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Saldo del conto a una data di apertura (es. 31/12/2025). */
public record SaldoInizialeRequest(
        @NotNull BigDecimal saldoIniziale,
        LocalDate dataSaldoIniziale
) {}

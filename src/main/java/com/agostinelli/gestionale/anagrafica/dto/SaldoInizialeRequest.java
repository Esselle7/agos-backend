package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Saldo del conto alla data di apertura scelta dall'utente (pagina Situazione iniziale). */
public record SaldoInizialeRequest(
        @NotNull BigDecimal saldoIniziale,
        LocalDate dataSaldoIniziale
) {}

package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record DashboardKpiDTO(
        SaldiDTO saldi,
        PeriodoDTO periodo,
        DeltaMesePrecedenteDTO vsMesePrecedente,
        Instant aggiornatoAl
) {
    public record SaldiDTO(
            ContoSaldoDTO bpm,
            ContoSaldoDTO creditAgricole,
            ContoSaldoDTO cassa,
            ContoSaldoDTO totale
    ) {}

    public record ContoSaldoDTO(
            BigDecimal saldo,
            BigDecimal variazioneNelPeriodo
    ) {}

    public record PeriodoDTO(
            LocalDate from,
            LocalDate to,
            BigDecimal totalEntrate,
            BigDecimal totalUscite,
            BigDecimal margine,
            BigDecimal marginePct,
            long nMovimenti
    ) {}

    public record DeltaMesePrecedenteDTO(
            BigDecimal entrateDelta,
            BigDecimal usciteDelta,
            BigDecimal margineDelta,
            BigDecimal deltaPercent
    ) {}
}

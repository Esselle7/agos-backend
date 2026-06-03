package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;

/**
 * KPI di qualità della classificazione import (ETL_CLASSIFICAZIONE_v2 §13).
 * I conti transitori (39.99.999 / 49.99.999) devono tendere a zero.
 */
public record ImportKpiDTO(
        long righeTotali,
        long importate,
        long ambigue,
        long scartate,
        long parcheggiate,
        long movimentiTransitori,
        BigDecimal saldoTransitori,
        double tassoAmbiguitaPct,
        double coperturaFornitoriPct
) {}

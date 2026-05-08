package com.agostinelli.gestionale.spese.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringExpensePlanSummaryDTO(
    UUID        id,
    String      descrizione,
    Short       contoBancarioId,
    String      contoBancarioNome,
    Integer     contoCoge,
    String      contoCogeDescrizione,
    BigDecimal  importoRata,
    BigDecimal  variazionePct,
    short       giornoDelMese,
    String      frequenza,
    int         numeroRate,
    LocalDate   dataPrimaRata,
    String      stato,
    // riepilogo rate
    int         ratePending,
    int         ratePaid,
    int         rateSkipped,
    int         rateCancelled,
    BigDecimal  totalePagato,
    BigDecimal  totaleResiduo
) {}

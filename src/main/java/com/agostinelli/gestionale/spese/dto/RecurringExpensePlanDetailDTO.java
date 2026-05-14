package com.agostinelli.gestionale.spese.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecurringExpensePlanDetailDTO(
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
    String      note,
    BigDecimal  totalePagato,
    BigDecimal  totaleResiduo,
    BigDecimal  totalePiano,
    BigDecimal  saldoContoBancario,
    List<RecurringExpenseInstallmentDTO> rate
) {}

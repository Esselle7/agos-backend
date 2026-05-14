package com.agostinelli.gestionale.spese.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringExpenseInstallmentDTO(
    UUID        id,
    int         numeroRata,
    LocalDate   dataScadenza,
    BigDecimal  importo,
    String      stato,
    UUID        movimentoId,
    String      note
) {}

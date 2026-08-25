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
    String      riferimentoEstrattoConto,
    BigDecimal  totalePagato,
    /** Quanto resta da SBORSARE: capitale + interessi delle rate PENDING. */
    BigDecimal  totaleResiduo,
    /** Quanto si deve ancora alla banca: solo la quota capitale delle rate PENDING. */
    BigDecimal  debitoResiduo,
    BigDecimal  totalePiano,
    BigDecimal  totaleInteressi,
    BigDecimal  totaleCapitale,
    String      tipoPiano,
    BigDecimal  tassoInteresseAnnuo,
    BigDecimal  importoDebitoIniziale,
    Integer     contoCogeInteressiId,
    String      contoCogeInteressiDescrizione,
    BigDecimal  saldoContoBancario,
    List<RecurringExpenseInstallmentDTO> rate
) {}

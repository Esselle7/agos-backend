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
    /** Testo con cui l'import riconosce l'addebito di questa rata (null = si usa il nome). */
    String      riferimentoEstrattoConto,
    // riepilogo rate
    int         ratePending,
    int         ratePaid,
    int         rateSkipped,
    int         rateCancelled,
    BigDecimal  totalePagato,
    /** Quanto resta da SBORSARE: capitale + interessi delle rate PENDING. */
    BigDecimal  totaleResiduo,
    /** Quanto si deve ancora alla banca: solo la quota capitale delle rate PENDING. */
    BigDecimal  debitoResiduo
) {}

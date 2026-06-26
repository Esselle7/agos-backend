package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Singola voce previsionale nella tabella dettaglio.
 * categoria:    MOVIMENTO | EVENTO | RATA_RICORRENTE | STIPENDIO
 * vista:        ECONOMICA | FINANZIARIA | ENTRAMBE
 * affidabilita: CERTO (contrattualizzato) | STIMATO (media storica ricavi cash)
 */
public record ForecastingDettaglioDTO(
        LocalDate data,
        String categoria,
        String descrizione,
        BigDecimal importoEntrata,
        BigDecimal importoUscita,
        String vista,
        String affidabilita) {}

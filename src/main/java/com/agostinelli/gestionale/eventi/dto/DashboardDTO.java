package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DashboardDTO(
        long totaleEventi,
        BigDecimal totaleIncassato,

        /** Somma dei movimenti USCITA collegati agli eventi nel periodo. */
        BigDecimal totaleCosti,

        /** totaleIncassato - totaleCosti. */
        BigDecimal profittoTotale,

        LocalDate from,
        LocalDate to
) {}

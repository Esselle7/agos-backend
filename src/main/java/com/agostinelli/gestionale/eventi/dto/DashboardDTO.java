package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KPI di dashboard del modulo eventi.
 *
 * I tre campi finanziari (totaleIncassato, totaleCosti, profittoTotale) sono
 * ADMIN-only e vengono restituiti come {@code null} ai DIPENDENTE: questi
 * ultimi vedono solo il conteggio degli eventi nel periodo.
 */
public record DashboardDTO(
        long totaleEventi,

        /** ADMIN-only. Somma degli importoIncassato degli eventi nel periodo. */
        BigDecimal totaleIncassato,

        /** ADMIN-only. Somma dei movimenti USCITA collegati agli eventi nel periodo. */
        BigDecimal totaleCosti,

        /** ADMIN-only. totaleIncassato - totaleCosti. */
        BigDecimal profittoTotale,

        LocalDate from,
        LocalDate to
) {}

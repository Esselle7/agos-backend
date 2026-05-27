package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uscita economicamente registrata (data_movimento <= oggi, stato=DA_LIQUIDARE)
 * ma finanziariamente ancora non liquidata: ha una data_liquidita attesa
 * (scadenza fornitore a 30/60/90 gg).
 */
public record UscitaDaLiquidareDTO(
        UUID        id,
        String      descrizione,
        BigDecimal  importo,
        LocalDate   dataMovimento,
        LocalDate   dataLiquidita,
        long        ggAllaScadenza,   // negativo = già scaduta
        String      urgenza,          // SCADUTA | ALTA | MEDIA | BASSA
        String      categoriaNome,
        String      businessUnitNome,
        String      fornitoreNome
) {}

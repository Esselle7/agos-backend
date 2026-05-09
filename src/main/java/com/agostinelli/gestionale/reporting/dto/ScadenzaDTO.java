package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ScadenzaDTO(
        String tipo,
        UUID referenceId,
        String descrizione,
        BigDecimal importoAtteso,
        LocalDate dataScadenza,
        String urgenza,
        String stato
) {}

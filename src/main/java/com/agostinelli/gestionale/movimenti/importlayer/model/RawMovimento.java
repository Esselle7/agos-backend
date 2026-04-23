package com.agostinelli.gestionale.movimenti.importlayer.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * Struttura dati normalizzata dopo il parsing grezzo, prima del mapping dominio.
 */
public record RawMovimento(
        LocalDate data,
        String descrizione,
        BigDecimal importo,
        String fonte,
        String riferimentoEsterno
) {}

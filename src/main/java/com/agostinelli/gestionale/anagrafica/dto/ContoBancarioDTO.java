package com.agostinelli.gestionale.anagrafica.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ContoBancarioDTO(
        Short id,
        String nome,
        String tipo,
        String iban,
        BigDecimal saldoCalcolato,
        BigDecimal saldoIniziale,
        LocalDate dataSaldoIniziale
) {}

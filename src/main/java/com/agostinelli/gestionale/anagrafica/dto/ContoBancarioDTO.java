package com.agostinelli.gestionale.anagrafica.dto;

import java.math.BigDecimal;

public record ContoBancarioDTO(
        Short id,
        String nome,
        String tipo,
        String iban,
        BigDecimal saldoCalcolato
) {}

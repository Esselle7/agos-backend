package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;

public record FatturatoPerBuDTO(
        short buId,
        String buNome,
        String colore,
        BigDecimal totEntrate,
        BigDecimal totUscite,
        BigDecimal margine,
        BigDecimal marginePct
) {}

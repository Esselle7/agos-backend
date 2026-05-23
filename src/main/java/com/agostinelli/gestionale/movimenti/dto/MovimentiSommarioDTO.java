package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.util.List;

public record MovimentiSommarioDTO(
    List<StatoSomma> perStato,
    BigDecimal totaleEntrate,
    BigDecimal totaleUscite,
    BigDecimal netto,
    long totaleCount
) {
    public record StatoSomma(
        String stato,
        BigDecimal totaleEntrate,
        BigDecimal totaleUscite,
        BigDecimal netto,
        long countEntrate,
        long countUscite
    ) {}
}

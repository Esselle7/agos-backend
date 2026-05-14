package com.agostinelli.gestionale.personale.dto;

import java.math.BigDecimal;
import java.util.List;

public record PersonaleCostoSummaryDTO(
        long totaleAttivi,
        BigDecimal costoMensileComplessivo,
        List<BuCosto> perBu
) {
    public record BuCosto(
            Short businessUnitId,
            String businessUnitNome,
            long count,
            BigDecimal costoMensile
    ) {}
}

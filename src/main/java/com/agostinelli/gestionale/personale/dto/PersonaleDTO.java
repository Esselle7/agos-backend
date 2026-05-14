package com.agostinelli.gestionale.personale.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PersonaleDTO(
        UUID id,
        String nome,
        String cognome,
        UUID mansioneId,
        String mansione,
        Short businessUnitId,
        String businessUnitNome,
        Integer centroDiCostoId,
        String centroDiCostoCodice,
        String centroDiCostoDescrizione,
        BigDecimal costoAziendaleMensile,
        boolean isActive
) {}

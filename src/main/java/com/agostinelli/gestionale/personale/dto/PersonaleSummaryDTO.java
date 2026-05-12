package com.agostinelli.gestionale.personale.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PersonaleSummaryDTO(
        UUID id,
        String nome,
        String cognome,
        String mansione,
        Short businessUnitId,
        String businessUnitNome,
        BigDecimal costoAziendaleMensile,
        boolean isActive
) {}

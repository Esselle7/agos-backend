package com.agostinelli.gestionale.personale.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PersonaleSummaryDTO(
        UUID id,
        String nome,
        String cognome,
        UUID mansioneId,
        String mansione,
        Short businessUnitId,
        String businessUnitNome,
        BigDecimal costoAziendaleMensile,
        String tipoRetribuzione,
        BigDecimal pagaOraria,
        boolean isActive
) {}

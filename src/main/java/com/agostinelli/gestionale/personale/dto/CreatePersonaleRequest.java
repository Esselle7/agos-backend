package com.agostinelli.gestionale.personale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreatePersonaleRequest(
        @NotBlank @Size(max = 100) String nome,
        @NotBlank @Size(max = 100) String cognome,
        @Size(max = 100) String mansione,
        Short businessUnitId,
        Integer centroDiCostoId,
        BigDecimal costoAziendaleMensile,
        Boolean isActive
) {}

package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CespiteRequest(
        @NotBlank String descrizione,
        @NotNull Integer contoCogeId,
        @NotNull @DecimalMin("0.01") BigDecimal costoStorico,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100.00") BigDecimal aliquotaAmmortamento,
        @NotNull LocalDate dataAcquisto,
        Boolean isActive
) {}

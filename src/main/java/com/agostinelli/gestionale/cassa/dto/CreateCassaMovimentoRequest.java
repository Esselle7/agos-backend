package com.agostinelli.gestionale.cassa.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateCassaMovimentoRequest(

        @NotBlank
        String tipo,

        @NotNull @DecimalMin("0.01")
        BigDecimal importo,

        @NotNull
        LocalDate dataMovimento,

        String descrizione,
        Integer contoCoge,
        Short businessUnitId,

        /**
         * Obbligatorio per PRELIEVO_DA_BANCA e VERSAMENTO_IN_BANCA:
         * identifica il conto bancario coinvolto nel trasferimento.
         */
        Short contoBancaId
) {}

package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EventoCreateRequest(

        @NotBlank
        String nome,

        @NotBlank
        String tipo,

        @NotNull
        LocalDate dataEvento,

        LocalDate dataPreventivo,

        @Positive
        BigDecimal importoTotalePreviventivato,

        @NotBlank
        String contattoNome,

        String contattoTelefono,

        String contattoEmail,

        Integer nOspiti,

        String note,

        /** BU di appartenenza; default BU2 – Cerimonie ed Eventi se null. */
        Short businessUnitId
) {}

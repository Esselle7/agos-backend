package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AggiungiPartecipanteRequest(

        /** ID del record personale (non users). */
        @NotNull
        UUID personaleId,

        String ruolo,

        BigDecimal costo,

        String note
) {}

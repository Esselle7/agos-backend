package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record EventoPartecipanteDTO(
        Long id,
        UUID eventoId,

        /** FK → personale.id. Mai users.id. */
        UUID personaleId,

        String ruolo,
        BigDecimal costo,
        String note
) {}

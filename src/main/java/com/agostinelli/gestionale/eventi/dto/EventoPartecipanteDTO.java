package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record EventoPartecipanteDTO(
        Long id,
        UUID eventoId,

        /** FK → personale.id. Mai users.id. */
        UUID personaleId,

        /** Dati anagrafici del dipendente (da JOIN con personale). */
        String nome,
        String cognome,

        /** Mansione del dipendente (da JOIN con mansioni via personale.mansione_id). */
        String mansione,

        /** Ruolo specifico per questo evento (può differire dalla mansione). */
        String ruolo,
        BigDecimal costo,
        String note
) {}

package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dettaglio di un dipendente partecipante a un evento.
 *
 * Il campo {@code costo} è ADMIN-only: per i DIPENDENTE viene restituito come
 * {@code null} per non esporre informazioni di costo del personale ad altri
 * dipendenti.
 */
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

        /** ADMIN-only. Costo previsto per la partecipazione a questo evento. */
        BigDecimal costo,

        String note
) {}

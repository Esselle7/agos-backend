package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EventoDTO(
        UUID id,
        String nome,
        String tipo,
        LocalDate dataEvento,
        LocalDate dataPreventivo,
        BigDecimal importoTotalePreviventivato,

        /** Aggiornato dal trigger DB trg_z_aggiorna_totali_evento – solo lettura. */
        BigDecimal importoIncassato,

        BigDecimal caparreIncassate,
        BigDecimal costiDirettiImputati,
        String stato,
        Short businessUnitId,
        String contattoNome,
        String contattoTelefono,
        String contattoEmail,
        Integer nOspiti,
        String note,

        /**
         * Visibile solo ai chiamanti con ruolo ADMIN.
         * Sempre null nelle risposte verso DIPENDENTE.
         */
        String noteAnnullamento,

        // ── Campi calcolati in Java ────────────────────────────────────────────

        /** importoTotalePreviventivato - importoIncassato. */
        BigDecimal importoResiduo,

        /** (importoIncassato / importoTotalePreviventivato) * 100. Null se preventivo non impostato. */
        BigDecimal percentualeIncassata,

        /** Somma dei movimenti USCITA collegati all'evento. Fonte primaria per i costi reali. */
        BigDecimal costiReali,

        /** importoIncassato - costiReali. */
        BigDecimal profitto,

        List<PagamentoEventoDTO> pagamenti,

        Instant createdAt,
        UUID createdBy
) {}

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

        /** Totale incassato — ricalcolato in Java dopo ogni pagamento. */
        BigDecimal importoIncassato,

        BigDecimal caparreIncassate,
        BigDecimal costiDirettiImputati,
        String stato,
        Short businessUnitId,
        String contattoNome,
        String contattoTelefono,
        String contattoEmail,
        Integer numeroTotalePartecipanti,
        Integer numeroBambini,
        List<String> allergie,
        String note,

        /** Visibile solo agli ADMIN. */
        String noteAnnullamento,

        // ── Campi calcolati ────────────────────────────────────────────────────

        /** importoTotalePreviventivato - importoIncassato. */
        BigDecimal importoResiduo,

        /** (importoIncassato / importoTotalePreviventivato) * 100. */
        BigDecimal percentualeIncassata,

        /** Somma movimenti USCITA collegati all'evento. */
        BigDecimal costiReali,

        /** importoIncassato - costiReali. */
        BigDecimal profitto,

        List<PagamentoEventoDTO> pagamenti,

        Instant createdAt,
        UUID createdBy
) {}

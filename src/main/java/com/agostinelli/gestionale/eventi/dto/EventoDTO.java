package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTO completo di un evento. La visibilità dei campi varia col ruolo:
 *
 *   ADMIN     → tutti i campi popolati
 *   DIPENDENTE → campi finanziari e note di annullamento sono {@code null};
 *                la lista pagamenti contiene comunque date+tipo+stato (per il journey)
 *                ma importo e note sono nascosti.
 */
public record EventoDTO(
        UUID id,
        String nome,
        String tipo,
        LocalDate dataEvento,
        LocalDate dataPreventivo,

        /** ADMIN-only. */
        BigDecimal importoTotalePreviventivato,

        /** ADMIN-only. */
        BigDecimal importoIncassato,

        /** ADMIN-only. */
        BigDecimal caparreIncassate,

        /** ADMIN-only. */
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

        /** ADMIN-only. */
        String noteAnnullamento,

        // ── Campi calcolati ────────────────────────────────────────────────────

        /** ADMIN-only. importoTotalePreviventivato - importoIncassato. */
        BigDecimal importoResiduo,

        /** ADMIN-only. (importoIncassato / importoTotalePreviventivato) * 100. */
        BigDecimal percentualeIncassata,

        /** ADMIN-only. Somma movimenti USCITA collegati all'evento. */
        BigDecimal costiReali,

        /** ADMIN-only. importoIncassato - costiReali. */
        BigDecimal profitto,

        // ── Date journey (visibili a tutti i ruoli) ────────────────────────────

        /**
         * Data della prima CAPARRA/ACCONTO non annullato (ENTRATA),
         * o {@code null} se nessun pagamento di conferma è stato registrato.
         * Usata dal frontend per il journey stepper anche per i DIPENDENTE.
         */
        LocalDate dataConferma,

        /**
         * Data del SALDO non annullato, o {@code null} se non saldato.
         */
        LocalDate dataSaldo,

        /**
         * Lista pagamenti. Per i non-ADMIN i campi {@code importo} e {@code note}
         * sono nascosti (= null), ma data, tipo e stato sono visibili.
         */
        List<PagamentoEventoDTO> pagamenti,

        Instant createdAt,
        UUID createdBy
) {}

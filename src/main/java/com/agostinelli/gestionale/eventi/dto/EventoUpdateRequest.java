package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EventoUpdateRequest(

        String nome,
        String tipo,
        LocalDate dataEvento,
        LocalDate dataPreventivo,

        @Positive
        BigDecimal importoTotalePreviventivato,

        String contattoNome,
        String contattoTelefono,
        String contattoEmail,

        @Min(1)
        Integer numeroTotalePartecipanti,

        Integer numeroBambini,

        /**
         * Se non null, sostituisce integralmente la lista allergie dell'evento.
         * Lista vuota = cancella tutte le allergie.
         */
        List<String> allergie,

        String note,

        /**
         * Transizione di stato manuale (solo ADMIN):
         * PREVENTIVATO→CONFERMATO, qualsiasi→ANNULLATO.
         */
        String stato,

        /** Obbligatoria in business logic quando stato=ANNULLATO. */
        String noteAnnullamento,

        Short businessUnitId
) {}

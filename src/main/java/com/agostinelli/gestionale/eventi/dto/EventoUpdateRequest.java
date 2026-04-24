package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

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
        Integer nOspiti,
        String note,

        /** Transizione di stato: PREVENTIVO→CONFERMATO→COMPLETATO o qualsiasi→ANNULLATO. */
        String stato,

        /** Obbligatoria in business logic quando stato=ANNULLATO. */
        String noteAnnullamento,

        Short businessUnitId
) {}

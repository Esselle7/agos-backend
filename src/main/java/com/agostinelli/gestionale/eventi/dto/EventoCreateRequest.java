package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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

        /** Numero totale partecipanti previsti — obbligatorio. */
        @NotNull @Min(1)
        Integer numeroTotalePartecipanti,

        Integer numeroBambini,

        /** Lista allergie dichiarate — opzionale, stringhe libere. */
        List<String> allergie,

        String note,

        /** BU di appartenenza; default BU2 – Cerimonie ed Eventi se null. */
        Short businessUnitId,

        /**
         * Dipendenti (personale.id) da associare all'evento.
         * Se null o vuota nessun partecipante viene creato.
         */
        List<UUID> personaleIds
) {}

package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PagamentoRequest(

        /** CAPARRA | ACCONTO | SALDO | PENALE. */
        @NotBlank
        String tipo,

        @NotNull @Positive
        BigDecimal importo,

        @NotNull
        LocalDate data,

        String note,

        /** ID del metodo di pagamento (FK metodi_pagamento.id). */
        @NotNull
        Integer metodoPagamentoId,

        /** ID del conto bancario su cui registrare il movimento. */
        @NotNull
        Short contoBancarioId,

        /**
         * Conto COGE di destinazione (opzionale).
         * Se null, il service seleziona automaticamente il primo conto ricavi (30.xx).
         */
        Integer contoCoge,

        /**
         * Ordinante del bonifico, come letto dall'estratto conto (A4). Lo passa l'import
         * ({@code ImportTriageService.riconciliaEvento}); dalla scheda evento è null, e in quel
         * caso la guardia anti-doppione resta quella di prima — fail-closed.
         */
        String controparte
) {}

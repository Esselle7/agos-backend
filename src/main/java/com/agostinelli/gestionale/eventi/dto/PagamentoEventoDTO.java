package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PagamentoEventoDTO(
        UUID movimentoId,

        /** CAPARRA | ACCONTO | SALDO | RIMBORSO. */
        String tipo,

        BigDecimal importo,
        LocalDate data,
        String note,
        String stato
) {}

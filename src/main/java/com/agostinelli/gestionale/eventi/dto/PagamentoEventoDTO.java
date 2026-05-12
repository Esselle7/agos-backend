package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PagamentoEventoDTO(
        UUID movimentoId,

        /** CAPARRA | ACCONTO | SALDO | PENALE. */
        String tipo,

        BigDecimal importo,

        /** Data effettiva del pagamento (data finanziaria). */
        LocalDate dataFinanziaria,

        String note,
        String stato
) {}

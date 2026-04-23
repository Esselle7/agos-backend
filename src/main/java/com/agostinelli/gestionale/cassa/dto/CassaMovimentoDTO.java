package com.agostinelli.gestionale.cassa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CassaMovimentoDTO(
        UUID id,
        String tipo,
        BigDecimal importo,
        LocalDate dataMovimento,
        String descrizione,
        Integer contoCoge,
        Short businessUnitId,
        Short contoBancaId,
        String stato,
        Instant createdAt,
        UUID createdBy
) {}

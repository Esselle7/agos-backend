package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** PATCH semantics: ogni campo null significa "non modificare". */
public record MovimentoUpdateRequest(
        String tipo,

        @DecimalMin("0.01")
        BigDecimal importo,

        BigDecimal importoLordo,
        BigDecimal aliquotaIva,
        LocalDate dataMovimento,
        LocalDate dataCompetenza,
        LocalDate dataLiquidita,
        Short contoBancarioId,
        Integer metodoPagamentoId,
        Short businessUnitId,
        Integer contoCoge,
        Long categoriaId,
        UUID fornitoreId,
        UUID eventoId,
        String tipoEventoMovimento,
        String descrizione,
        String note,
        String riferimentoEsterno,
        String fonte,
        String allegatoPath
) {}

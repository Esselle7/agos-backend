package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MovimentoDTO(
        UUID id,
        String tipo,
        BigDecimal importo,
        BigDecimal importoImponibile,
        BigDecimal importoIva,
        BigDecimal importoCommissione,
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
        String stato,
        String fonte,
        String riferimentoEsterno,
        String allegatoPath,
        Instant createdAt,
        UUID createdBy
) {}

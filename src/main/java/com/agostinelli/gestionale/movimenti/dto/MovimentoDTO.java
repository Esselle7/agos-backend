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
        /** Data di competenza economica (= data_movimento, sempre valorizzata). */
        LocalDate dataMovimento,
        LocalDate dataCompetenza,
        /** Data di liquidazione effettiva. null = DA_LIQUIDARE. */
        LocalDate dataFinanziaria,
        /** Scadenza finanziaria attesa. Obbligatoria se dataFinanziaria è null. */
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

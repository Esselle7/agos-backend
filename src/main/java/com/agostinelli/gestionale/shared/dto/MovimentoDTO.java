package com.agostinelli.gestionale.shared.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO condiviso che rappresenta un movimento economico/finanziario.
 * Usato da tutti i moduli: ristorazione, cerimonie, spaccio, manutenzione.
 */
public record MovimentoDTO(
        UUID id,
        String tipo,
        BigDecimal importo,
        LocalDate dataMovimento,
        LocalDate dataCompetenza,
        /** Data di liquidazione effettiva. null = DA_LIQUIDARE. */
        LocalDate dataFinanziaria,
        LocalDate dataLiquidita,
        String canale,
        UUID contoId,
        String contoNome,
        UUID businessUnitId,
        String businessUnitNome,
        UUID categoriaId,
        String categoriaNome,
        UUID sottocategoriaId,
        String sottocategoriaNome,
        UUID fornitoreId,
        String fornitoreNome,
        UUID eventoId,
        String eventoNome,
        String tipoEventoMovimento,
        String descrizione,
        String note,
        BigDecimal importoLordo,
        BigDecimal importoCommissione,
        BigDecimal aliquotaIva,
        BigDecimal importoIva,
        String stato,
        String fonte,
        String allegatoPath,
        Instant createdAt,
        UUID createdBy
) {}

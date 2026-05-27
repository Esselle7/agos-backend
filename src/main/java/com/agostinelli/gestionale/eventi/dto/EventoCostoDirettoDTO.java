package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Vista di un costo diretto reale evento (DJ, Torta, Personalizzato). */
public record EventoCostoDirettoDTO(
        Long id,
        String tipoCosto,
        String voce,
        String etichetta,
        BigDecimal importo,
        // Movimento collegato
        UUID movimentoId,
        LocalDate movimentoData,
        String contoCodice,
        String note,
        Instant createdAt
) {}

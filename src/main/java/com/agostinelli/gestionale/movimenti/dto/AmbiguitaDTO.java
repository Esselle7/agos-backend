package com.agostinelli.gestionale.movimenti.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AmbiguitaDTO(
        UUID id,
        UUID importLogId,
        int rigaNumero,
        String fonte,
        Map<String, String> rawData,
        String motivo,
        String stato,
        UUID movimentoId,
        Instant classificatoAt,
        String noteOperatore
) {}

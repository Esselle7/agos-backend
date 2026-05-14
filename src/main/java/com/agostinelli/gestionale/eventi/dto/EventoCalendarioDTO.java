package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record EventoCalendarioDTO(
        UUID id,
        String nome,
        LocalDate dataEvento,
        String stato,
        BigDecimal importoTotale,
        BigDecimal importoResiduo,

        /**
         * Colore esadecimale associato allo stato:
         * PREVENTIVATO=#FFA500, CONFERMATO=#2196F3, SALDATO=#4CAF50, ANNULLATO=#9E9E9E.
         */
        String coloreStato
) {
    private static final Map<String, String> COLORI = Map.of(
            "PREVENTIVATO", "#FFA500",
            "CONFERMATO",   "#2196F3",
            "SALDATO",      "#4CAF50",
            "ANNULLATO",    "#9E9E9E"
    );

    public static String colorePerStato(String stato) {
        return COLORI.getOrDefault(stato, "#9E9E9E");
    }
}

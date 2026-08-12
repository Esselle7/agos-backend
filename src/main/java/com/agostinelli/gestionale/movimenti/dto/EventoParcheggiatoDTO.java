package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Voce nella coda eventi_da_riconciliare (ETL v2 §5/§9.3): un incasso-evento messo da
 * parte dal Gate B, in attesa di riconciliazione o classificazione manuale.
 */
public record EventoParcheggiatoDTO(
        UUID id,
        String fonte,
        String chiaveAggancio,
        LocalDate dataMovimento,
        BigDecimal importo,
        String tipo,
        Short contoBancarioId,
        String descrizioneNorm,
        String tipoEventoPresunto,   // CAPARRA | ACCONTO | SALDO | AFFITTO_SALA | null
        String keywordMatch,
        String controparteNome,
        String controparteIban,
        LocalDate dataEventoEstratta,
        String stato,                // DA_RICONCILIARE | RICONCILIATO | SCARTATO

        /**
         * Evento proposto dal sistema, da pre-selezionare in UI. Valorizzato SOLO quando nome
         * controparte e data evento coincidono entrambi: sul solo nome la precisione misurata è
         * del 20% (4 falsi positivi su 5), inaccettabile su un aggancio che muove denaro.
         * null = nessuna proposta, sceglie l'operatore.
         */
        UUID eventoSuggeritoId,
        String eventoSuggeritoNome
) {}

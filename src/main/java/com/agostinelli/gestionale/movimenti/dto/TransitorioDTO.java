package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Movimento registrato su un conto transitorio (39.99.999 ricavi / 49.99.999 costi)
 * in attesa di catalogazione (ETL v2 §6 C3 / §13). Espone i dati del movimento + le
 * entità ri-estratte dalla descrizione (IBAN/nome controparte) per il triage assistito.
 */
public record TransitorioDTO(
        UUID id,
        String tipo,                 // ENTRATA | USCITA
        BigDecimal importo,
        LocalDate dataMovimento,
        String descrizione,
        String cogeCodiceAttuale,    // 39.99.999 | 49.99.999
        UUID fornitoreId,
        Short contoBancarioId,
        String ibanEstratto,         // dalla descrizione (best-effort)
        String controparteEstratta,  // beneficiario/ordinante ri-estratto
        /**
         * Chiave del gruppo del wizard §7.1: righe dello STESSO esercente. null = la riga è una
         * decisione a sé (nessuna controparte affidabile: POS, effetti/RiBa, causali generiche).
         */
        String gruppo,
        /** Data in cui la vendita è avvenuta, se la causale la dichiara ("… DEL 10/01/26"). */
        String dataOperazione,
        /** Circuito dell'incasso POS letto dalla causale (NEXI, NUMIA-INTER…); null se non è POS. */
        String circuitoPos,
        /** Riscontro Billy della stessa giornata sullo stesso conto — di GIORNATA, non 1:1. */
        RiscontroBillyDTO riscontroBilly,
        /** Conto proposto da una firma keyword appresa — SUGGERIMENTO, mai applicato da solo. */
        Integer cogeSuggeritoId,
        /** Il "perché" del suggerimento, in italiano: si mostra accanto alla proposta. */
        String motivoSuggerimento
) {
    /**
     * Che cosa Billy ha registrato lo stesso giorno, sullo stesso conto, di questa riga bancaria.
     *
     * <p><b>È un confronto di GIORNATA, non della singola transazione.</b> L'abbinamento
     * scontrino↔accredito non esiste nei dati: la riconciliazione POS ripartisce i TOTALI di
     * periodo (misurato l'11/08/2026 — l'aggancio per data operazione dà 9 righe su 10 a zero, e
     * per data di accredito gli importi non corrispondono). Serve a far vedere all'operatore
     * l'ordine di grandezza e a segnalare uno scarto, non a quadrare al centesimo.
     *
     * @param scontrini quante righe di ricavo Billy quel giorno su quel conto
     * @param totale    la loro somma
     * @param scarto    importo della riga bancaria − totale Billy (positivo = la banca ha di più)
     */
    public record RiscontroBillyDTO(long scontrini, java.math.BigDecimal totale,
                                    java.math.BigDecimal scarto) {}
}

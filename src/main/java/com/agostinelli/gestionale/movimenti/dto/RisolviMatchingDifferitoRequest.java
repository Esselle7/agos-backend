package com.agostinelli.gestionale.movimenti.dto;

/**
 * Risoluzione di un matching differito (V11):
 *  - COLLEGA: liquida il movimento Da Liquidare esistente usando i dati della riga banca
 *    (dataFinanziaria = dataBanca, contoBancarioId, metodoPagamentoId, stato = REGISTRATO).
 *    Opzionalmente l'utente può fornire un metodoPagamentoId diverso da quello risolto
 *    in import (utile se il mapping non lo aveva determinato).
 *  - IGNORA: crea comunque un nuovo movimento dalla riga banca (falso positivo del match).
 *    Il movimento Da Liquidare originale resta aperto.
 */
public record RisolviMatchingDifferitoRequest(
        String azione,             // COLLEGA | IGNORA
        Integer metodoPagamentoId, // opzionale per COLLEGA (override del metodo risolto in import)
        String nota
) {}

package com.agostinelli.gestionale.movimenti.dto;

/** Richiesta di anteprima keyword: descrizione (e sorgente best-effort) della riga da catalogare. */
public record KeywordAnteprimaRequest(String descrizione, String sorgente) {}

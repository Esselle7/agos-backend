package com.agostinelli.gestionale.movimenti.dto;

/**
 * Esito della rivalutazione dei conflitti MATCH dopo che l'utente ha sistemato le firme:
 * {@code chiusi} = conflitti non più ambigui chiusi da soli; {@code catalogati} = movimenti
 * incastrati ri-catalogati sul target unico rimasto (tolti da "Da catalogare").
 */
public record RivalutazioneConflittiDTO(int chiusi, int catalogati) {}

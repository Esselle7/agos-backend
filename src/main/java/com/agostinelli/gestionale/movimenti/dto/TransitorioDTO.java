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
        String controparteEstratta   // beneficiario/ordinante ri-estratto
) {}

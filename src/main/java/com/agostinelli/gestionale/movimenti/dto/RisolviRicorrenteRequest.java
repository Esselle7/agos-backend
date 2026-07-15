package com.agostinelli.gestionale.movimenti.dto;

/**
 * Risoluzione di una ricorrente parcheggiata (V9 + V22):
 *  - CONFERMA: crea il movimento contabile reale dalla riga parcheggiata (rata già addebitata
 *    in banca ma non ancora a libro). Su USCITA {@code cogeId} è obbligatorio; su ENTRATA è
 *    ignorato (il backend forza 90.01.001 «Finanziamenti ricevuti»);
 *  - IGNORA: la archivia (non è una ricorrente da contabilizzare).
 * L'azione COLLEGA è stata rimossa (→ 400 AZIONE_NON_VALIDA).
 */
public record RisolviRicorrenteRequest(
        String azione,     // CONFERMA | IGNORA
        Integer cogeId,    // obbligatorio per CONFERMA su USCITA; ignorato su ENTRATA
        String nota
) {}

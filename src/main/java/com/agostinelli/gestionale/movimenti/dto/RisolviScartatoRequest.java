package com.agostinelli.gestionale.movimenti.dto;

/**
 * Azione sulla coda «Righe fuori dai conti»: CONTABILIZZA («mettila nei conti») crea il movimento,
 * IGNORA («lasciala fuori») chiude la riga senza toccare i saldi.
 *
 * <p>Dal client arriva SOLO il conto CoGe: importo, tipo, data, conto bancario e metodo li ridecide
 * il server dal grezzo della riga (SPEC righe-fuori-dai-conti.md, invariante I3).
 */
public record RisolviScartatoRequest(
        String azione,     // CONTABILIZZA | IGNORA
        Integer cogeId     // obbligatorio su CONTABILIZZA; ignorato su IGNORA
) {}

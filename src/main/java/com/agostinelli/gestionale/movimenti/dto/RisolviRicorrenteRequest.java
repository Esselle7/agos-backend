package com.agostinelli.gestionale.movimenti.dto;

/**
 * Risoluzione di una ricorrente parcheggiata (V9 + V22):
 *  - COLLEGA: aggancia la riga alla rata di un piano ricorrente. Se la rata è PENDING la paga con
 *    la data reale dell'addebito; se è già PAID non crea nulla e riusa il movimento esistente —
 *    è il modo per NON duplicare quello che lo scheduler ha già generato.
 *    Richiede {@code pianoId} e {@code rataId};
 *  - CONFERMA: crea un movimento contabile isolato dalla riga (rata già addebitata in banca ma
 *    senza un piano a cui agganciarsi, es. dopo la ricreazione dei piani sul debito residuo).
 *    Su USCITA {@code cogeId} è obbligatorio; su ENTRATA è ignorato (il backend forza
 *    90.01.001 «Finanziamenti ricevuti»);
 *  - IGNORA: la archivia (non è una ricorrente da contabilizzare).
 *
 * Vedi docs/specs/ricorrenti-collega-da-import.md.
 */
public record RisolviRicorrenteRequest(
        String azione,     // COLLEGA | CONFERMA | IGNORA
        Integer cogeId,    // obbligatorio per CONFERMA su USCITA; ignorato su ENTRATA
        java.util.UUID pianoId,   // obbligatorio per COLLEGA
        java.util.UUID rataId,    // obbligatorio per COLLEGA
        String nota
) {}

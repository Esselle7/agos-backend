package com.agostinelli.gestionale.movimenti.dto;

/**
 * Un passo dell'elaborazione dell'import, con quanto è durato <b>davvero</b>.
 *
 * <p><b>Perché esiste.</b> L'import congiunto è un blocco muto: l'utente carica tre file, aspetta,
 * e riceve un riepilogo. Non sa se il sistema sta lavorando o se si è piantato. Questi passi sono
 * la risposta, e sono <b>misurati</b>: {@code millis} è il tempo vero speso in quella fase, il
 * {@code dettaglio} è il numero vero che quella fase ha prodotto. Nessuna barra a tempo.
 *
 * <p>Perché nella risposta e non su un canale che trasmette mentre lavora (SSE): misurato il
 * 13/08/2026 sull'import reale di luglio, l'intera elaborazione dura <b>~1,3 s</b>
 * (185 righe, 154 bancarie). Un trasporto nuovo per 1,3 secondi è complessità che non si ripaga —
 * i passi si mostrano appena arrivano, con la loro durata accanto.
 */
public record FaseImportDTO(
        /** Il nome del passo in italiano, come lo direbbe chi lo sta aspettando. */
        String nome,
        /** Il numero che questo passo ha prodotto: senza, il passo è una promessa. */
        String dettaglio,
        /** Durata misurata, in millisecondi. */
        int millis
) {}

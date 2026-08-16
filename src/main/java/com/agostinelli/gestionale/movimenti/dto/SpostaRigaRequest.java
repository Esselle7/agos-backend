package com.agostinelli.gestionale.movimenti.dto;

/**
 * «Questa non è una spesa, è un incasso evento» / «è una rata»: rimanda una riga bancaria alla
 * coda che sa lavorarla.
 *
 * <p>Serve perché il motore riconosce gli incassi-evento da una keyword nella causale, e una
 * causale come «8RIST 14/06/26» non ne ha nessuna: la riga finisce fra le spese da sistemare, dove
 * però la risposta giusta non c'è — un ricavo-evento non si cataloga a mano, nasce dal modulo
 * Eventi (invariante DACLASS). Invece di aggiungere una scorciatoia che aggiri quell'invariante,
 * la riga torna in coda e riprende il percorso normale.
 */
public record SpostaRigaRequest(
        /** EVENTO = incassi evento · RICORRENTE = rate. */
        String destinazione,
        /** Perché l'hai spostata: resta scritto sulla riga di coda. Facoltativo. */
        String nota
) {}

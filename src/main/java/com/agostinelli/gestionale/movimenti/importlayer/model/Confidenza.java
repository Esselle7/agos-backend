package com.agostinelli.gestionale.movimenti.importlayer.model;

/**
 * Quanto il motore SA di una riga (SPEC import-v2 §3, R1/R3). Non è una probabilità: è la
 * qualità del segnale che ha deciso, e determina se la riga si scrive da sola o si propone.
 */
public enum Confidenza {

    /**
     * Campo strutturale, non un indovinello. Elenco CHIUSO (R3):
     * regola data-driven con match EQUALS/IN_LIST · girosalto marcato dal normalizzatore ·
     * metodo ADDEBITO_CONTO · CoGe 40.02.* da causale strutturata · mittente in causale
     * (STRIPE / AGEA / versamento soci) · riga POS con scontrino Billy agganciato su DEL+importo ·
     * firma keyword promossa (§4, oggi N = ∞ ⇒ nessuna).
     * Solo queste si contabilizzano da sole sul conto definitivo.
     */
    CERTA,

    /**
     * Il motore ha un'ipotesi buona — una firma keyword appresa, un alias fornitore — ma è
     * un'ipotesi su un nome di fornitore, non un campo. Si mostra, non si applica: la riga va
     * sul transitorio con la proposta allegata e il perché in chiaro.
     */
    PROPOSTA,

    /** Nessun segnale: transitorio 39/49.99.999. Il denaro non si perde mai, la categoria sì. */
    IGNOTA
}

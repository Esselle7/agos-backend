package com.agostinelli.gestionale.movimenti.importlayer.reconcile;

/**
 * Natura dell'incasso prodotto dalla riconciliazione a periodo (PROMPT-RICONCILIAZIONE-PERIODO §4):
 * <b>Billy è la verità</b> per i ricavi elettronici; le banche servono solo a ripartire l'incasso
 * sui conti e a fare da controllo di quadratura. Determina come lo scontrino Billy viene mappato.
 */
public enum EsitoMatch {
    /**
     * Ricavo elettronico spaccio (non-agriturismo) da uno scontrino Billy. Categoria/COGE da Billy,
     * conto bancario (BPM/CA) assegnato dalla ripartizione deterministica di periodo (Step 4).
     */
    RICAVO_POS,
    /** Scontrino Billy contante → movimento su Cassa (conto 3), categoria da Billy. */
    CONTANTI
}

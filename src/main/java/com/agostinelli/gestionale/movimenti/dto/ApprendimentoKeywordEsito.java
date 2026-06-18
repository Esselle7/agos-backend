package com.agostinelli.gestionale.movimenti.dto;

import java.util.List;
import java.util.UUID;

/**
 * Esito dell'apprendimento keyword da una catalogazione manuale: quante firme create/aggiornate
 * e gli eventuali conflitti aperti (così la UI può avvisare l'utente — §4.5/§5A).
 */
public record ApprendimentoKeywordEsito(
        int firmeCreate,
        int firmeAggiornate,
        List<UUID> conflitti
) {
    public boolean conflittoGenerato() {
        return conflitti != null && !conflitti.isEmpty();
    }
}

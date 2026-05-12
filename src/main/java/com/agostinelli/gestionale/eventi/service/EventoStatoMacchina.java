package com.agostinelli.gestionale.eventi.service;

import com.agostinelli.gestionale.eventi.domain.Evento;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.infrastructure.exception.ForbiddenException;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;

/**
 * Macchina a stati per le transizioni manuali degli eventi.
 *
 * Flusso ammesso:
 *   PREVENTIVATO → CONFERMATO  (qualsiasi ruolo — importo > 0 richiesto)
 *   CONFERMATO   → SALDATO     (qualsiasi ruolo — residuo ≤ €0.01 richiesto)
 *   qualsiasi    → ANNULLATO   (solo ADMIN + noteAnnullamento obbligatoria)
 *   SALDATO      → qualsiasi   → sempre vietato (stato terminale)
 */
final class EventoStatoMacchina {

    private EventoStatoMacchina() {}

    static void valida(Evento e, String nuovoStato, boolean isAdmin, String noteAnnullamento) {
        String attuale = e.stato;

        // SALDATO è terminale
        if ("SALDATO".equals(attuale)) {
            throw new ForbiddenException("Evento saldato: stato non modificabile");
        }

        // → ANNULLATO: solo ADMIN + nota obbligatoria
        if ("ANNULLATO".equals(nuovoStato)) {
            if (!isAdmin) {
                throw new ForbiddenException("Solo gli ADMIN possono annullare un evento");
            }
            if (noteAnnullamento == null || noteAnnullamento.isBlank()) {
                throw new ApiException(Response.Status.BAD_REQUEST, "NOTE_ANNULLAMENTO_OBBLIGATORIE",
                        "noteAnnullamento è obbligatoria per annullare l'evento");
            }
            return;
        }

        // PREVENTIVATO → CONFERMATO: importo > 0 richiesto
        if ("PREVENTIVATO".equals(attuale) && "CONFERMATO".equals(nuovoStato)) {
            if (e.importoTotalePreviventivato == null
                    || e.importoTotalePreviventivato.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_PREVENTIVATO_MANCANTE",
                        "importoTotalePreviventivato > 0 è richiesto per confermare l'evento");
            }
            return;
        }

        // CONFERMATO → SALDATO: residuo ≤ €0.01 richiesto
        if ("CONFERMATO".equals(attuale) && "SALDATO".equals(nuovoStato)) {
            if (e.importoTotalePreviventivato == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_PREVENTIVATO_MANCANTE",
                        "importoTotalePreviventivato richiesto per completare l'evento");
            }
            BigDecimal incassato = e.importoIncassato != null ? e.importoIncassato : BigDecimal.ZERO;
            BigDecimal residuo = e.importoTotalePreviventivato.subtract(incassato);
            if (residuo.compareTo(new BigDecimal("0.01")) > 0) {
                throw new ApiException(Response.Status.CONFLICT, "RESIDUO_NON_AZZERATO",
                        "Residuo ancora da incassare: EUR " + residuo);
            }
            return;
        }

        throw new ApiException(Response.Status.BAD_REQUEST, "TRANSIZIONE_NON_AMMESSA",
                "Transizione manuale " + attuale + " → " + nuovoStato + " non consentita");
    }
}

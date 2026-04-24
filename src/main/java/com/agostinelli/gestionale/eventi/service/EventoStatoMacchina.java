package com.agostinelli.gestionale.eventi.service;

import com.agostinelli.gestionale.eventi.domain.Evento;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.infrastructure.exception.ForbiddenException;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;

/**
 * Macchina a stati per le transizioni di stato degli eventi cerimonie.
 * Estratta in una classe separata per garantire testabilità unitaria
 * senza dipendenze da DB, HTTP o CDI.
 *
 * Flusso ammesso:
 *   PREVENTIVO → CONFERMATO (richiede importoTotalePreviventivato > 0)
 *   CONFERMATO → COMPLETATO (richiede residuo ≤ 0.01)
 *   qualsiasi  → ANNULLATO  (richiede ADMIN + noteAnnullamento)
 *   COMPLETATO → qualsiasi  → sempre vietato
 */
final class EventoStatoMacchina {

    private EventoStatoMacchina() {}

    /**
     * Valida la transizione di stato {@code nuovoStato} sull'evento {@code e}.
     * Lancia un'eccezione se la transizione non è ammessa.
     *
     * @throws ForbiddenException se mancano i permessi ADMIN o l'evento è COMPLETATO
     * @throws ApiException       se la transizione viola una regola di business
     */
    static void valida(Evento e, String nuovoStato, boolean isAdmin, String noteAnnullamento) {
        String attuale = e.stato;

        // COMPLETATO → qualsiasi: evento completato è immutabile per design
        if ("COMPLETATO".equals(attuale)) {
            throw new ForbiddenException("Evento completato non modificabile");
        }

        // QUALSIASI → ANNULLATO: richiede ruolo ADMIN e noteAnnullamento obbligatoria
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

        // PREVENTIVO → CONFERMATO: importoTotalePreviventivato > 0 obbligatorio
        if ("PREVENTIVO".equals(attuale) && "CONFERMATO".equals(nuovoStato)) {
            if (e.importoTotalePreviventivato == null
                    || e.importoTotalePreviventivato.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_PREVENTIVATO_MANCANTE",
                        "importoTotalePreviventivato > 0 è richiesto per confermare l'evento");
            }
            return;
        }

        // CONFERMATO → COMPLETATO: residuo da incassare ≤ 0.01 (tolleranza centesimi)
        if ("CONFERMATO".equals(attuale) && "COMPLETATO".equals(nuovoStato)) {
            if (e.importoTotalePreviventivato == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_PREVENTIVATO_MANCANTE",
                        "importoTotalePreviventivato non impostato");
            }
            BigDecimal residuo = e.importoTotalePreviventivato.subtract(e.importoIncassato);
            if (residuo.compareTo(new BigDecimal("0.01")) > 0) {
                throw new ApiException(Response.Status.CONFLICT, "RESIDUO_NON_AZZERATO",
                        "Residuo da incassare: EUR " + residuo
                        + ". L'evento può essere completato solo a saldo azzerato (tolleranza €0.01)");
            }
            return;
        }

        // Transizione non definita nel flusso di business
        throw new ApiException(Response.Status.BAD_REQUEST, "TRANSIZIONE_NON_AMMESSA",
                "Transizione " + attuale + " → " + nuovoStato + " non consentita");
    }
}

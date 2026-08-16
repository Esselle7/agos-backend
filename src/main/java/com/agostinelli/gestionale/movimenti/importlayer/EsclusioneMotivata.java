package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import jakarta.ws.rs.core.Response;

/**
 * R9 — «escluso di proposito» è una casa, non un secchio muto: chiudere una riga bancaria senza
 * contabilizzarla richiede un motivo SCRITTO. Vale su tutte e quattro le code di esclusione
 * (scartati IGNORA · ambiguità SCARTA · eventi SCARTA · ricorrenti IGNORA), lato SERVIZIO: la
 * guardia della UI è cortesia, questa è il vincolo.
 */
final class EsclusioneMotivata {

    /** Lunghezza minima: «no», «ok», «x» non sono motivi — fra sei mesi non spiegano niente. */
    private static final int MIN = 3;

    static String obbligatorio(String nota) {
        String n = nota == null ? "" : nota.trim();
        if (n.length() < MIN) {
            throw new ApiException(Response.Status.BAD_REQUEST, "MOTIVO_OBBLIGATORIO",
                    "Per lasciare questa riga fuori dai conti serve scriverne il motivo: "
                    + "è denaro vero passato in banca, e fra sei mesi nessuno ricorderà perché.");
        }
        return n;
    }

    private EsclusioneMotivata() {}
}

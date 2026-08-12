package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import jakarta.ws.rs.core.Response;

/**
 * Invariante DACLASS in un punto solo: il mastro dei ricavi evento ({@code 30.02.*}) è
 * RISERVATO al modulo Eventi.
 *
 * <p>Un movimento su {@code 30.02.*} senza {@code evento_id} è un ricavo che non compare nel
 * bilancio di nessun evento — misurati 6 movimenti orfani per 3.425,60 € e 13 firme keyword che
 * insegnavano al motore a rifarlo da solo (audit-catena-import-2026-08-11.md §6.2/2 e §7.6).
 *
 * <p>Tre chiamanti, una sola stringa: apprendimento/CRUD keyword
 * ({@code KeywordLearningService}), coda «Righe fuori dai conti» e catalogazione dei transitori
 * ({@code ImportTriageService}).
 */
public final class CogeRiservatoEventi {

    private static final String PREFISSO = "30.02.";

    private CogeRiservatoEventi() {}

    /** True se il codice appartiene al mastro riservato (null = no). */
    public static boolean riservato(String cogeCodice) {
        return cogeCodice != null && cogeCodice.startsWith(PREFISSO);
    }

    /**
     * Fail fast e rumoroso: 409 {@code COGE_RISERVATO_EVENTI} con la via d'uscita, non un codice.
     *
     * @param cogeCodice codice del conto scelto (non l'id)
     */
    public static void vieta(String cogeCodice) {
        if (riservato(cogeCodice)) {
            throw new ApiException(Response.Status.CONFLICT, "COGE_RISERVATO_EVENTI",
                    "Il conto " + cogeCodice + " è riservato al modulo Eventi: un ricavo-evento senza "
                    + "evento collegato non compare nel bilancio di nessun evento. Attribuisci "
                    + "l'incasso dalla coda «Incassi evento», non da qui.");
        }
    }
}

package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import jakarta.ws.rs.core.Response;

/**
 * I due conti d'attesa dell'import — e la regola che nessuno ci finisca <b>per scelta</b>.
 *
 * <p>{@code 39.99.999} (Ricavi da classificare) e {@code 49.99.999} (Costi da classificare) sono
 * dove l'import parcheggia una riga quando non sa che voce sia: ci si arriva dal motore, mai da una
 * decisione dell'operatore. Fino al 13/08/2026 però il boundary di
 * {@code ImportTriageService.classificaTransitorio} verificava solo che il conto scelto
 * <b>esistesse</b> e non fosse un {@code 30.02.*}: i due transitori passavano entrambi i controlli,
 * e «catalogare una riga sul conto da catalogare» era un'operazione accettata dal server.
 *
 * <p><b>Non è teoria.</b> Il 12/08/2026 un'ENTRATA POS da 100,00 € (movimento
 * {@code 332c2471-0320-4078-a496-81716f3db7b0}) è passata da {@code 39.99.999} a {@code 49.99.999}:
 * un incasso spostato nel parcheggio dei costi. Il denaro non si perde — il contatore la vede
 * comunque «da catalogare» e l'invariante chiude — ma la riga dichiara di essere stata classificata
 * come non classificata, per giunta col segno rovesciato.
 *
 * <p>Il motore questa regola ce l'ha già in lettura ({@code suggerimentoKeyword} scarta le proposte
 * che puntano al transitorio: «proporre il conto su cui la riga già sta non è una proposta, è
 * rumore»). Qui la stessa regola arriva in <b>scrittura</b>.
 *
 * <h2>Un chiamante solo, e non è una svista</h2>
 * {@code contabilizzaScartato} accetta il transitorio come destinazione ed è giusto così: una riga
 * della coda «Righe fuori dai conti» è denaro che <b>non è nei libri</b>, e portarla sul transitorio
 * la fa entrare — da «fuori dai conti» a «da catalogare» è un passo avanti vero, anche se la
 * categoria resta ignota. In {@code classificaTransitorio} invece la riga <b>è già lì</b>: la stessa
 * destinazione non fa entrare niente, può solo rovesciare il segno. Stesso conto, due significati
 * diversi — la guardia sta dove il significato manca.
 *
 * <p>Stessa forma di {@link CogeRiservatoEventi}: una sola stringa, un solo punto.
 */
public final class CogeTransitorio {

    public static final String RICAVI = "39.99.999";
    public static final String COSTI = "49.99.999";

    private CogeTransitorio() {}

    /** True se il codice è uno dei due conti d'attesa (null = no). */
    public static boolean transitorio(String cogeCodice) {
        return RICAVI.equals(cogeCodice) || COSTI.equals(cogeCodice);
    }

    /**
     * Fail fast: 409 {@code COGE_TRANSITORIO}. Il messaggio dice la via d'uscita, non solo il no —
     * chi voleva togliersi la riga dai piedi ha una risposta legittima («lascia in sospeso»), che
     * non richiede di scrivere una classificazione falsa.
     *
     * @param cogeCodice codice del conto scelto (non l'id)
     */
    public static void vieta(String cogeCodice) {
        if (transitorio(cogeCodice)) {
            throw new ApiException(Response.Status.CONFLICT, "COGE_TRANSITORIO",
                    "Il conto " + cogeCodice + " è il parcheggio dell'import, non una voce di "
                    + "bilancio: catalogare una riga qui significa dichiararla «non classificata». "
                    + "Scegli la voce vera, oppure lascia la riga in sospeso — resta dov'è e non "
                    + "succede niente.");
        }
    }
}

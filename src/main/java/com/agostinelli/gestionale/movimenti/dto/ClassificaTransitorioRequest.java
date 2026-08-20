package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Classificazione manuale di un movimento su conto transitorio: lo sposta sul conto
 * COGE/BU corretti e, opzionalmente, apprende le KEYWORD dalla descrizione così i prossimi
 * import catalogano da soli una riga simile (PROMPT-KEYWORD-LEARNING.md §4.4).
 */
public record ClassificaTransitorioRequest(
        @NotNull Integer cogeId,
        @NotNull Short businessUnitId,
        UUID fornitoreId,
        boolean apprendiKeyword,  // se true: estrae firme IDENTITA dalla descrizione → target scelto
        /**
         * Le firme che l'operatore ha davvero visto e approvato nel wizard, dopo aver spento i
         * token di troppo. <b>Se è presente vince su {@code apprendiKeyword}</b> (I1): è l'unica
         * fonte che coincide con ciò che stava a schermo. {@code null} = comportamento storico,
         * le firme se le estrae il server. Lista vuota = questa volta non si impara niente.
         *
         * <p>I token si possono solo TOGLIERE, mai inventare: il server rifiuta (400) un token che
         * non appartiene alla causale, perché una firma così non potrebbe MAI scattare su quella
         * riga (il match è in AND sul token-set) e l'operatore non avrebbe modo di accorgersene.
         */
        List<FirmaSceltaDTO> firme,
        String nota
) {

    /** Senza firme scelte a mano: è il percorso storico, il server se le estrae da solo. */
    public ClassificaTransitorioRequest(Integer cogeId, Short businessUnitId, UUID fornitoreId,
                                        boolean apprendiKeyword, String nota) {
        this(cogeId, businessUnitId, fornitoreId, apprendiKeyword, null, nota);
    }

    /** Una firma approvata a mano: i token rimasti accesi. Una firma senza token non si impara. */
    public record FirmaSceltaDTO(List<String> token) {}
}

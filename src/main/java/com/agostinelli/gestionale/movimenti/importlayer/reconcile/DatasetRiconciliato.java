package com.agostinelli.gestionale.movimenti.importlayer.reconcile;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;

import java.util.List;

/**
 * Esito della riconciliazione a periodo (PROMPT-RICONCILIAZIONE-PERIODO §4): cosa va
 * contabilizzato (Billy = verità), cosa resta in attesa, e la quadratura informativa.
 *
 * @param daMappare         righe da mappare/persistere, in ordine deterministico:
 *                          ricavi POS Billy (arricchiti) + contanti Billy (→ Cassa) +
 *                          righe banca NON-POS (passthrough ai gate AS-IS). Le righe banca
 *                          POS NON sono qui: non si contabilizzano (sono duplicati di Billy).
 * @param billyContabilizzati scontrini Billy che generano un movimento (ricavi POS + contanti).
 * @param inAttesaAccredito coda fondo: vendite Billy dopo l'ultima DEL banca → NON contabilizzate
 *                          ora, segnalate; verranno bookate al prossimo import (dedup su DCW).
 * @param eventiAttesi      scontrini Billy elettronici agriturismo → incasso-evento atteso
 *                          (il ricavo arriva dal bonifico parcheggiato): nessun ricavo spaccio.
 * @param quadratura        pannello di quadratura di periodo (informativo).
 * @param stat              contatori per il log/diagnostica.
 */
public record DatasetRiconciliato(
        List<RawMovimentoArricchito> daMappare,
        List<RawMovimento> billyContabilizzati,
        List<RawMovimento> inAttesaAccredito,
        List<RawMovimento> eventiAttesi,
        QuadraturaPeriodo quadratura,
        Statistiche stat
) {
    /** Contatori della riconciliazione (per import_log e asserzioni di test). */
    public record Statistiche(
            int righeBancaPos,
            int testaEsclusa,
            int ricaviPos,
            int contanti,
            int eventiAttesi,
            int inAttesaAccredito,
            int assegnatiBpm,
            int assegnatiCa,
            int bancaNonPos
    ) {}
}

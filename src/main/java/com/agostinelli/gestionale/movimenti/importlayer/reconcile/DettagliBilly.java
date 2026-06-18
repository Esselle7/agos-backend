package com.agostinelli.gestionale.movimenti.importlayer.reconcile;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dati di booking di uno scontrino Billy nell'import congiunto a periodo
 * (PROMPT-RICONCILIAZIONE-PERIODO §4). Lo scontrino Billy È il ricavo: la categoria nasce da
 * Billy, il conto bancario è quello assegnato dalla ripartizione di periodo (per i ricavi POS)
 * oppure la Cassa (per i contanti).
 *
 * <p>Il COGE è espresso per <b>codice</b> (mai ID): il riconciliatore è una funzione pura senza
 * DB, la risoluzione codice→ID resta nel mapping engine (come il resto del codebase).
 *
 * @param esito            {@link EsitoMatch#RICAVO_POS} o {@link EsitoMatch#CONTANTI}
 * @param cogeCodice       codice COGE da Billy (es. "30.03.001"); null → transitorio ricavi
 * @param bu               business unit; null se non determinabile (→ transitorio)
 * @param aliquotaIva      aliquota IVA della categoria Billy (es. 0.10)
 * @param metodoCodice     metodo da bookare: POS_BPM / POS_CA_NEXI (ripartizione) / CONTANTI
 * @param contoBancarioId  conto bancario assegnato: 1=BPM, 2=CA, 3=Cassa
 * @param riferimentiBilly riferimenti esterni degli scontrini Billy (tracciabilità/dedup)
 */
public record DettagliBilly(
        EsitoMatch esito,
        String cogeCodice,
        Short bu,
        BigDecimal aliquotaIva,
        String metodoCodice,
        Short contoBancarioId,
        List<String> riferimentiBilly
) {}

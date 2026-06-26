package com.agostinelli.gestionale.movimenti.importlayer.reconcile;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;

import java.math.BigDecimal;

/**
 * Resolver mono-categoria dello scontrino Billy → (COGE, BU, IVA)
 * (REFACTOR-IMPORT-CONGIUNTO §FASE2, fatto #4: Billy è 100% mono-categoria per scontrino).
 *
 * <p>Lo scontrino valorizza esattamente UNA colonna categoria; la prima positiva vince.
 * Il COGE è per codice (mai ID): la risoluzione codice→ID resta nel mapping engine.
 * Categoria ignota/assente → {@code null}: l'incasso finisce sul transitorio 39.99.999
 * e nel triage (nessuna imputazione arbitraria).
 *
 * <p>Le colonne carne/agriturismo/ortofrutta arrivano dai campi tipizzati del
 * {@link RawMovimento}; "Prodotti trasformati" dai campi grezzi (estratto dal parser).
 */
public final class BillyCategoria {

    private BillyCategoria() {}

    /** Esito della classificazione di categoria (codice COGE + BU + aliquota IVA). */
    public record Esito(String cogeCodice, short bu, BigDecimal aliquotaIva) {}

    // COGE per codice (allineati a MovimentoMappingEngineImpl / seed V4).
    private static final String COGE_AGRITURISMO = "30.01.001"; // ristorazione (Billy cassa)
    private static final String COGE_CARNE_10 = "30.03.001";    // vendita carni e salumi (IVA 10%)
    private static final String COGE_ORTOFRUTTA_4 = "30.03.002"; // ortofrutta e trasformati (IVA 4%)

    private static final BigDecimal IVA_10 = new BigDecimal("0.10");
    private static final BigDecimal IVA_04 = new BigDecimal("0.04");

    private static final short BU_RISTORAZIONE = 1;
    private static final short BU_SPACCIO = 3;

    /** Categoria dello scontrino, o {@code null} se non determinabile (→ transitorio). */
    public static Esito classifica(RawMovimento billy) {
        if (positive(billy.billyAgriturismo())) return new Esito(COGE_AGRITURISMO, BU_RISTORAZIONE, IVA_10);
        if (positive(billy.billyCarne10())) return new Esito(COGE_CARNE_10, BU_SPACCIO, IVA_10);
        if (positive(billy.billyOrtofrutta4())) return new Esito(COGE_ORTOFRUTTA_4, BU_SPACCIO, IVA_04);
        // "Prodotti trasformati" (CSV corrispettivi): accorpato a ortofrutta/trasformati 4%.
        if (positive(rawAmount(billy, "PRODOTTI_TRASFORMATI"))) {
            return new Esito(COGE_ORTOFRUTTA_4, BU_SPACCIO, IVA_04);
        }
        // "Servizi" (CSV corrispettivi): ristorazione (PROMPT-RICONCILIAZIONE-PERIODO §4 Step 1).
        if (positive(rawAmount(billy, "SERVIZI"))) {
            return new Esito(COGE_AGRITURISMO, BU_RISTORAZIONE, IVA_10);
        }
        return null;
    }

    private static BigDecimal rawAmount(RawMovimento n, String key) {
        if (n.rawOriginale() == null) return null;
        String v = n.rawOriginale().campi().get(key);
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }
}

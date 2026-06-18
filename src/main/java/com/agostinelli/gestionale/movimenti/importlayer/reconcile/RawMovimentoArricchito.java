package com.agostinelli.gestionale.movimenti.importlayer.reconcile;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;

/**
 * Riga pronta per il mapping nell'import congiunto a periodo (PROMPT-RICONCILIAZIONE-PERIODO §4).
 *
 * <ul>
 *   <li>{@code dettagli != null} → ricavo da bookare coi dati Billy: la riga sorgente
 *       ({@link #riga()}) è uno <b>scontrino Billy</b> (ricavo POS o contante), categoria/conto
 *       già risolti in {@link DettagliBilly}. L'overload del mapping engine la gestisce
 *       direttamente.</li>
 *   <li>{@code dettagli == null} → riga banca NON-POS (costo, evento, SDD, commissione, Stripe,
 *       giroconto, Satispay…): delegata invariata a {@code map(RawMovimento)} (gate AS-IS).</li>
 * </ul>
 */
public record RawMovimentoArricchito(RawMovimento riga, DettagliBilly dettagli) {

    public static RawMovimentoArricchito passthrough(RawMovimento riga) {
        return new RawMovimentoArricchito(riga, null);
    }

    public static RawMovimentoArricchito arricchito(RawMovimento riga, DettagliBilly dettagli) {
        return new RawMovimentoArricchito(riga, dettagli);
    }

    public boolean isArricchito() {
        return dettagli != null;
    }

    /** Alias storico: la riga sorgente (scontrino Billy o riga banca non-POS). */
    public RawMovimento banca() {
        return riga;
    }
}

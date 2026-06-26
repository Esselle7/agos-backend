package com.agostinelli.gestionale.movimenti.importlayer;

/**
 * Strategia di importazione per fonte. Espone il parser corretto (per chiave fonte)
 * e il normalizzatore condiviso; l'orchestrazione vive in {@link MovimentoImportService}.
 *
 * Chiavi fonte (fonteStr): IMPORT_BILLY | IMPORT_BANCA_BPM | IMPORT_BANCA_CA.
 * Fonte DB ({@link #fonte()}): IMPORT_BILLY | IMPORT_BANCA.
 */
public interface ImportStrategy {

    /** true se questa strategia gestisce la chiave fonte richiesta. */
    boolean supports(String fonteStr);

    /** Fonte persistita su DB (lk_fonti_movimento). */
    String fonte();

    /** Parser adeguato alla chiave fonte (BPM vs CA condividono la strategia). */
    MovimentoParser parserFor(String fonteStr);

    MovimentoNormalizer getNormalizer();
}

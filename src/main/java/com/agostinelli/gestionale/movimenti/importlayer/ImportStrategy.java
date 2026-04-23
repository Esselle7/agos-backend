package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.model.ImportResult;

import java.io.InputStream;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * This interface is intentionally left as a placeholder.
 * Full ETL processing will be introduced in a dedicated phase
 * to avoid coupling domain logic with file parsing logic.
 */
public interface ImportStrategy {
    /** La fonte di importazione supportata da questa strategia (IMPORT_BILLY, IMPORT_BANCA, …). */
    String supports();

    ImportResult process(InputStream file);
}

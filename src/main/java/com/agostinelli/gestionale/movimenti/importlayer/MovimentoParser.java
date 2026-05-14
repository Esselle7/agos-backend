package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;

import java.io.InputStream;
import java.util.List;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * This interface is intentionally left as a placeholder.
 * Full ETL processing will be introduced in a dedicated phase
 * to avoid coupling domain logic with file parsing logic.
 */
public interface MovimentoParser {
    List<RawRow> parse(InputStream file);
}

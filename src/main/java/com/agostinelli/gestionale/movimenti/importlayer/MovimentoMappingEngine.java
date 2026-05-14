package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * This interface is intentionally left as a placeholder.
 * Full ETL processing will be introduced in a dedicated phase
 * to avoid coupling domain logic with file parsing logic.
 */
public interface MovimentoMappingEngine {
    MappingResult map(RawMovimento raw);
}

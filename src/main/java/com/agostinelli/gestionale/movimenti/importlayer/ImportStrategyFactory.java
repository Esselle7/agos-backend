package com.agostinelli.gestionale.movimenti.importlayer;

import jakarta.enterprise.context.ApplicationScoped;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * This class is intentionally left as a placeholder.
 * Full ETL processing will be introduced in a dedicated phase
 * to avoid coupling domain logic with file parsing logic.
 *
 * TODO: iniettare una List<ImportStrategy> CDI e risolvere per source type.
 */
@ApplicationScoped
public class ImportStrategyFactory {
    // FUTURE: risolverà la strategia corretta in base alla fonte (BILLY, BPM, CA)
}

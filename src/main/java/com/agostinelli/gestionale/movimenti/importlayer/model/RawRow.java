package com.agostinelli.gestionale.movimenti.importlayer.model;

import java.util.Map;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * This model is intentionally left as a placeholder.
 * Full ETL processing will be introduced in a dedicated phase
 * to avoid coupling domain logic with file parsing logic.
 */
public record RawRow(int riga, Map<String, String> campi) {}

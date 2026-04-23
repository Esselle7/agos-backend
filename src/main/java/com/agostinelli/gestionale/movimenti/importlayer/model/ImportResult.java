package com.agostinelli.gestionale.movimenti.importlayer.model;

import com.agostinelli.gestionale.movimenti.dto.BulkImportResponse;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * Risultato dell'intera operazione di import da un file esterno.
 */
public record ImportResult(BulkImportResponse response, String fonte, String filename) {}

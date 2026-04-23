package com.agostinelli.gestionale.movimenti.dto;

import java.util.List;

public record BulkImportResponse(
        int importati,
        int duplicati,
        int errori,
        List<ImportError> dettaglioErrori
) {}

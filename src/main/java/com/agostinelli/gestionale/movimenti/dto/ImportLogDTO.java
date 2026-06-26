package com.agostinelli.gestionale.movimenti.dto;

import java.time.Instant;
import java.util.UUID;

public record ImportLogDTO(
        UUID id,
        String fonte,
        String filename,
        Instant dataImport,
        Integer righeTotali,
        Integer righeImportate,
        Integer righeErrore,
        Integer righeDuplicate,
        Integer righeAmbigue,
        Integer righeAmbigueClassificate,
        String stato,
        UUID importedBy
) {}

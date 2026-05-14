package com.agostinelli.gestionale.movimenti.importlayer.model;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * Risultato del mapping da RawMovimento a MovimentoCreateRequest.
 */
public record MappingResult(MovimentoCreateRequest request, boolean success, String errore) {}

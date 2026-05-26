package com.agostinelli.gestionale.movimenti.importlayer.model;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;

/**
 * Risultato del mapping da RawMovimento a MovimentoCreateRequest.
 * outcome:
 *   SUCCESS        -> request valorizzato, pronto per la persistenza
 *   AMBIGUOUS      -> riga non classificabile, va in import_ambiguita
 *   GIROCONTO_SKIP -> trasferimento interno, tracciato come ambiguità ma non è un errore
 *   ERROR          -> errore di mapping non recuperabile
 */
public record MappingResult(
        MappingOutcome outcome,
        MovimentoCreateRequest request,  // valorizzato solo se SUCCESS
        String motivoAmbiguita,          // valorizzato se AMBIGUOUS / GIROCONTO_SKIP / ERROR
        RawMovimento rawNormalizzato     // sempre: per logging
) {

    public enum MappingOutcome { SUCCESS, AMBIGUOUS, GIROCONTO_SKIP, ERROR }

    public static MappingResult success(MovimentoCreateRequest request, RawMovimento raw) {
        return new MappingResult(MappingOutcome.SUCCESS, request, null, raw);
    }

    public static MappingResult ambiguous(String motivo, RawMovimento raw) {
        return new MappingResult(MappingOutcome.AMBIGUOUS, null, motivo, raw);
    }

    public static MappingResult giroconto(RawMovimento raw) {
        return new MappingResult(MappingOutcome.GIROCONTO_SKIP, null, "GIROCONTO_SKIP", raw);
    }
}

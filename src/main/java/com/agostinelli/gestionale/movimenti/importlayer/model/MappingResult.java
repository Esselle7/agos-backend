package com.agostinelli.gestionale.movimenti.importlayer.model;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;

/**
 * Risultato del mapping da RawMovimento a MovimentoCreateRequest.
 * outcome:
 *   SUCCESS          -> request valorizzato, pronto per la persistenza
 *   AMBIGUOUS        -> riga non classificabile, va in import_ambiguita
 *   SKIP_POS         -> incasso POS/Satispay duplicato di Billy → import_scartati
 *   SKIP_GIROCONTO   -> trasferimento interno tra conti propri → import_scartati
 *   SKIP_RICORRENTE  -> spesa ricorrente/finanziamento (modulo dedicato) → import_scartati (traccia leggera)
 *   PARK_EVENTO      -> voce evento separata → eventi_da_riconciliare (non è un movimento, per ora)
 *   ERROR            -> errore di mapping non recuperabile
 *
 * Gli esiti SKIP_* sostituiscono il vecchio GIROCONTO_SKIP (Gate A, ETL v2 §4):
 * non sono ambiguità né errori, sono esclusioni deterministiche tracciate.
 */
public record MappingResult(
        MappingOutcome outcome,
        MovimentoCreateRequest request,  // valorizzato solo se SUCCESS
        String motivoAmbiguita,          // valorizzato se AMBIGUOUS / SKIP_* / ERROR
        ParkEvento park,                 // valorizzato solo se PARK_EVENTO
        String trace,                    // spiegazione del percorso decisionale (per il log per-import)
        RawMovimento rawNormalizzato,    // sempre: per logging
        String keywordConflittoSig       // signature_hash se la riga ha innescato un conflitto keyword di MATCH
) {

    public enum MappingOutcome {
        SUCCESS, AMBIGUOUS, ERROR,
        SKIP_POS, SKIP_GIROCONTO, SKIP_RICORRENTE,
        PARK_EVENTO;

        public boolean isSkip() {
            return this == SKIP_POS || this == SKIP_GIROCONTO || this == SKIP_RICORRENTE;
        }
    }

    /** Copia con la traccia decisionale valorizzata (per il logging passo-passo). */
    public MappingResult withTrace(String t) {
        return new MappingResult(outcome, request, motivoAmbiguita, park, t, rawNormalizzato, keywordConflittoSig);
    }

    /** Copia che segnala un conflitto keyword di MATCH (riga booked sul transitorio). */
    public MappingResult withKeywordConflitto(String sig) {
        return new MappingResult(outcome, request, motivoAmbiguita, park, trace, rawNormalizzato, sig);
    }

    public static MappingResult success(MovimentoCreateRequest request, RawMovimento raw) {
        return new MappingResult(MappingOutcome.SUCCESS, request, null, null, null, raw, null);
    }

    public static MappingResult ambiguous(String motivo, RawMovimento raw) {
        return new MappingResult(MappingOutcome.AMBIGUOUS, null, motivo, null, null, raw, null);
    }

    public static MappingResult skip(MappingOutcome outcome, RawMovimento raw) {
        return new MappingResult(outcome, null, outcome.name(), null, null, raw, null);
    }

    public static MappingResult parkEvento(ParkEvento park, RawMovimento raw) {
        return new MappingResult(MappingOutcome.PARK_EVENTO, null, "PARK_EVENTO", park, null, raw, null);
    }
}

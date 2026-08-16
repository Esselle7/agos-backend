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
 *
 * <p><b>confidenza</b> (R1) dice QUANTO il motore sa: su SUCCESS decide se il movimento nasce sul
 * conto definitivo (CERTA) o sul transitorio con la proposta allegata (PROPOSTA/IGNOTA). Su
 * PROPOSTA il campo {@link #proposta()} porta il conto NON applicato e il perché in chiaro.
 */
public record MappingResult(
        MappingOutcome outcome,
        MovimentoCreateRequest request,  // valorizzato solo se SUCCESS
        String motivoAmbiguita,          // valorizzato se AMBIGUOUS / SKIP_* / ERROR
        ParkEvento park,                 // valorizzato solo se PARK_EVENTO
        String trace,                    // spiegazione del percorso decisionale (per il log per-import)
        RawMovimento rawNormalizzato,    // sempre: per logging
        String keywordConflittoSig,      // signature_hash se la riga ha innescato un conflitto keyword di MATCH
        Confidenza confidenza,           // R1: CERTA | PROPOSTA | IGNOTA (null sugli esiti non-SUCCESS)
        Proposta proposta                // R4/R6: valorizzata solo se confidenza = PROPOSTA
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
        return new MappingResult(outcome, request, motivoAmbiguita, park, t, rawNormalizzato,
                keywordConflittoSig, confidenza, proposta);
    }

    /** Copia che segnala un conflitto keyword di MATCH (riga booked sul transitorio). */
    public MappingResult withKeywordConflitto(String sig) {
        return new MappingResult(outcome, request, motivoAmbiguita, park, trace, rawNormalizzato,
                sig, confidenza, proposta);
    }

    /** Copia con confidenza ed eventuale proposta non applicata. */
    public MappingResult with(Confidenza c, Proposta p) {
        return new MappingResult(outcome, request, motivoAmbiguita, park, trace, rawNormalizzato,
                keywordConflittoSig, c, p);
    }

    public static MappingResult success(MovimentoCreateRequest request, RawMovimento raw) {
        return new MappingResult(MappingOutcome.SUCCESS, request, null, null, null, raw, null, null, null);
    }

    public static MappingResult ambiguous(String motivo, RawMovimento raw) {
        return new MappingResult(MappingOutcome.AMBIGUOUS, null, motivo, null, null, raw, null, null, null);
    }

    public static MappingResult skip(MappingOutcome outcome, RawMovimento raw) {
        return new MappingResult(outcome, null, outcome.name(), null, null, raw, null, null, null);
    }

    public static MappingResult parkEvento(ParkEvento park, RawMovimento raw) {
        return new MappingResult(MappingOutcome.PARK_EVENTO, null, "PARK_EVENTO", park, null, raw, null, null, null);
    }
}

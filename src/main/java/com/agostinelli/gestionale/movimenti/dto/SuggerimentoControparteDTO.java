package com.agostinelli.gestionale.movimenti.dto;

import java.util.UUID;

/**
 * Suggerimento di controparte per il triage (ETL v2 §8.2): top-N candidati ordinati
 * per similarità, con il COGE/BU storicamente associati.
 */
public record SuggerimentoControparteDTO(
        UUID controparteId,
        String nome,
        String iban,
        UUID fornitoreId,
        Integer cogeDefaultId,
        String cogeCodice,
        Short buDefault,
        double similarita
) {}

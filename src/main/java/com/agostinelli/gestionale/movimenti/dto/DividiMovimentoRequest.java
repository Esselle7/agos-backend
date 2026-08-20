package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Divisione di un movimento cumulativo (RiBa/effetti) in N quote.
 * Spec: docs/specs/debug-con-cliente/riba-split-importo.md
 *
 * <p>Le quote sono il DENARO: la somma deve fare esattamente l'importo del padre. La validazione
 * di forma sta qui (bean validation), quella di quadratura nel service — è un controllo fra campi
 * e deve valere anche per chiamanti che non passino dalla REST.
 */
public record DividiMovimentoRequest(
        @NotNull @Size(min = 2, message = "Servono almeno due quote: dividere in una sola non è dividere")
        List<@Valid Quota> quote
) {
    public record Quota(
            @NotNull @Positive BigDecimal importo,
            @NotNull Integer contoCogeId,
            @NotNull Short businessUnitId,
            UUID fornitoreId,
            String descrizione
    ) {}
}

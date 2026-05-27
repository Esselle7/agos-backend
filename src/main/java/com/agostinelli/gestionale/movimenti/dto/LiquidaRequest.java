package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Corpo della richiesta PATCH /movimenti/{id}/liquida.
 *
 * contoBancarioId  – conto con cui viene effettuato il pagamento (obbligatorio).
 * metodoPagamentoId – metodo di pagamento (opzionale; può essere completato in seguito).
 */
public record LiquidaRequest(
        @NotNull Short   contoBancarioId,
        Integer          metodoPagamentoId
) {}

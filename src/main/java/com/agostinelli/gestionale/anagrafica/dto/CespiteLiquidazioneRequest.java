package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Liquidazione differita di un acquisto cespite rimasto DA_LIQUIDARE (R13).
 *
 * {@code contoBancarioId} obbligatorio (con quale conto/cassa si paga); {@code dataPagamento}
 * opzionale (default = oggi); il metodo è derivato dal tipo conto se non indicato.
 */
public record CespiteLiquidazioneRequest(
        @NotNull Short contoBancarioId,
        LocalDate dataPagamento,
        Integer metodoPagamentoId
) {}

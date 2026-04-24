package com.agostinelli.gestionale.eventi.dto;

/**
 * Wrapper interno restituito da EventiService.registraPagamento().
 * Porta il DTO del pagamento e il flag per il response header X-Suggest-Completamento.
 */
public record RegistraPagamentoResult(
        PagamentoEventoDTO dto,

        /**
         * true se tipo=SALDO e importoResiduo <= 0 dopo il pagamento.
         * Suggerisce al chiamante di passare l'evento in stato COMPLETATO.
         */
        boolean suggerisciCompletamento
) {}

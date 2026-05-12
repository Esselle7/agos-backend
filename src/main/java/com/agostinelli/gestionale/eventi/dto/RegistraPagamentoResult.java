package com.agostinelli.gestionale.eventi.dto;

/** Risultato interno di EventiService.registraPagamento(). */
public record RegistraPagamentoResult(PagamentoEventoDTO dto, boolean suggestCompletamento) {}

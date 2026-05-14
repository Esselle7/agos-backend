package com.agostinelli.gestionale.anagrafica.dto;

public record PianoContiCogeDTO(
        Integer id,
        String codice,
        String nome,
        String tipo,
        Integer parentId,
        Integer livello
) {}

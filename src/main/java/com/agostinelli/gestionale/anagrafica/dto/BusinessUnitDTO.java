package com.agostinelli.gestionale.anagrafica.dto;

public record BusinessUnitDTO(
        Short id,
        String codice,
        String nome,
        String colore,
        String descrizione
) {}

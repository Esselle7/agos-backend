package com.agostinelli.gestionale.anagrafica.dto;

public record CentroDiCostoDTO(
        Integer id,
        String codice,
        String descrizione,
        Short businessUnitId
) {}

package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCategoriaRequest(
        @NotBlank @Size(max = 100) String nome,
        @NotBlank String tipo,
        Long parentId,
        @NotNull Short buId,
        int ordinamento
) {}

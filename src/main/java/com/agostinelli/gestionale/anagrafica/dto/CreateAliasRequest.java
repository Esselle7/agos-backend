package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAliasRequest(
        @NotBlank @Size(max = 255) String pattern,
        @NotBlank String matchType
) {}

package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFornitoreRequest(
        @NotBlank @Size(max = 255) String ragioneSociale,
        @Size(max = 100) String alias,
        @Size(max = 11) String piva,
        @Size(max = 7) String codiceSdi,
        Integer cogeDefaultId,
        Short buDefaultId,
        String note
) {}

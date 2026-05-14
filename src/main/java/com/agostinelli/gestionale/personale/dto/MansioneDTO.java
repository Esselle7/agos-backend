package com.agostinelli.gestionale.personale.dto;

import java.util.UUID;

public record MansioneDTO(
        UUID id,
        String nome,
        boolean isActive
) {}

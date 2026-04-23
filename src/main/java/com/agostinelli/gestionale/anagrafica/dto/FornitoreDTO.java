package com.agostinelli.gestionale.anagrafica.dto;

import java.util.List;
import java.util.UUID;

public record FornitoreDTO(
        UUID id,
        String ragioneSociale,
        String alias,
        String piva,
        String codiceSdi,
        Integer cogeDefaultId,
        Short buDefaultId,
        String note,
        List<AliasDTO> aliasList
) {}

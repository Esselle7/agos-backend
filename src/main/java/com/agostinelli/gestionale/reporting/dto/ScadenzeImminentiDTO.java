package com.agostinelli.gestionale.reporting.dto;

import java.util.List;

public record ScadenzeImminentiDTO(
        List<ScadenzaDTO> eventi,
        List<ScadenzaDTO> rateRicorrenti
) {}

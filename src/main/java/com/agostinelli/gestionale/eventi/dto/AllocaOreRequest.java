package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Ore da allocare a un partecipante ORARIA. */
public record AllocaOreRequest(
        @NotNull @Positive BigDecimal ore
) {}

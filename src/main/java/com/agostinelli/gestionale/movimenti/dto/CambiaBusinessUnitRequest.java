package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;

/** Sposta un movimento su un'altra Business Unit (dimensione analitica: non tocca i saldi). */
public record CambiaBusinessUnitRequest(@NotNull Short businessUnitId) {}

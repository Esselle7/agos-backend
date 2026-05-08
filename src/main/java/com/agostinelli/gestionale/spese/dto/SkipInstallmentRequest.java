package com.agostinelli.gestionale.spese.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SkipInstallmentRequest(
    @NotNull
    @Pattern(regexp = "RIMANDA|ACCORPA")
    String modalita   // RIMANDA = aggiunge rata in fondo; ACCORPA = somma alla prossima
) {}

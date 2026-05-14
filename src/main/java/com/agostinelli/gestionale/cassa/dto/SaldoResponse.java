package com.agostinelli.gestionale.cassa.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SaldoResponse(BigDecimal saldo, LocalDate aggiornatoAl) {}

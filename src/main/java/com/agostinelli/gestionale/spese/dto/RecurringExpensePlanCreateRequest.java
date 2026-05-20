package com.agostinelli.gestionale.spese.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringExpensePlanCreateRequest(

    @NotBlank
    @Size(max = 255)
    String descrizione,

    @NotNull
    Short contoBancarioId,

    @NotNull
    Integer contoCoge,

    @NotNull
    @DecimalMin("0.01")
    BigDecimal importoRata,

    BigDecimal variazionePct,      // default 0

    @NotNull
    @Min(1) @Max(28)
    Short giornoDelMese,

    @NotNull
    @Pattern(regexp = "MENSILE|BIMESTRALE|TRIMESTRALE")
    String frequenza,

    @NotNull
    @Min(1)
    Integer numeroRate,

    @NotNull
    LocalDate dataInizio,          // mese/anno di partenza; il giorno viene sostituito con giornoDelMese

    String note,

    @Pattern(regexp = "FLAT|FINANZIAMENTO")
    String tipoPiano,              // default "FLAT" se null

    BigDecimal importoDebitoIniziale,  // richiesto se tipoPiano = FINANZIAMENTO

    @DecimalMin("0.001")
    BigDecimal tassoInteresseAnnuo,    // % annuo, es. 3.5 = 3,5%. Richiesto se FINANZIAMENTO

    Integer contoCogeInteressiId       // COGE di tipo ONERE_FINANZIARIO. Richiesto se FINANZIAMENTO
) {}

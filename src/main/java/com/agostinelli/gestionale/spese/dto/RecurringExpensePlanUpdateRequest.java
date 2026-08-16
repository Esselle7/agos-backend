package com.agostinelli.gestionale.spese.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Modifica di un piano già creato. Si toccano SOLO i campi che descrivono il piano e ne governano
 * il RICONOSCIMENTO nell'import; importi, numero rate, giorno e tipo piano restano fuori perché
 * cambiarli imporrebbe di rigenerare l'ammortamento (Σ quote capitale = debito iniziale) su rate
 * che possono essere già pagate: è una feature a sé, con la sua SPEC.
 */
public record RecurringExpensePlanUpdateRequest(

    @NotBlank
    @Size(max = 255)
    String descrizione,

    @NotNull
    Short contoBancarioId,

    @Size(max = 120)
    String riferimentoEstrattoConto,

    String note
) {}

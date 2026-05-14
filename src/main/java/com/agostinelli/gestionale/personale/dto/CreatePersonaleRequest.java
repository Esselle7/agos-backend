package com.agostinelli.gestionale.personale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreatePersonaleRequest(
        @NotBlank @Size(max = 100) String nome,
        @NotBlank @Size(max = 100) String cognome,
        /** Nome della mansione: se non esiste viene creata automaticamente. */
        @Size(max = 100) String mansione,
        Short businessUnitId,
        /**
         * Il centro di costo viene derivato automaticamente dalla BU.
         * Questo campo è ignorato; mantenuto per compatibilità con eventuali
         * client già integrati.
         */
        Integer centroDiCostoId,
        BigDecimal costoAziendaleMensile,
        Boolean isActive
) {}

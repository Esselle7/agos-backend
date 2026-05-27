package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Richiesta di creazione di un costo diretto reale evento (genera movimento USCITA).
 * {@code etichetta} è obbligatoria solo per la voce CUSTOM.
 */
public record EventoCostoDirettoRequest(

        /** FISSO | VARIABILE. */
        @NotBlank
        String tipoCosto,

        /** DJ | TORTA | CUSTOM. */
        @NotBlank
        String voce,

        /** Obbligatoria solo se voce = CUSTOM, altrimenti precompilata dal service. */
        String etichetta,

        /** Importo del costo. */
        BigDecimal importo,

        String note
) {}

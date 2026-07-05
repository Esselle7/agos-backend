package com.agostinelli.gestionale.eventi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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

        /** BU a cui imputare il movimento di costo. Se null, la BU dell'evento. */
        Short businessUnitId,

        /**
         * Opzionale: importo addebitato al cliente (ricarico). Se valorizzato, crea una
         * voce di preventivo collegata (origine COSTO_DIRETTO) che alza il preventivato
         * senza generare movimenti. Es. DJ pagato 200 → addebitato 250.
         */
        @Positive
        BigDecimal importoAddebitoCliente,

        String note
) {}

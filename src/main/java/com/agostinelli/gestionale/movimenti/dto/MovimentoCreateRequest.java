package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record MovimentoCreateRequest(

        @NotBlank
        String tipo,

        @NotNull @DecimalMin("0.01")
        BigDecimal importo,

        /**
         * Importo lordo pre-commissione (es. POS, Satispay).
         * Se presente e > importo: importoCommissione = importoLordo - importo.
         */
        BigDecimal importoLordo,

        /** Aliquota IVA come decimale (es. 0.10 per il 10%). */
        BigDecimal aliquotaIva,

        @NotNull
        LocalDate dataMovimento,

        LocalDate dataCompetenza,
        LocalDate dataLiquidita,

        @NotNull
        Short contoBancarioId,

        @NotNull
        Integer metodoPagamentoId,

        @NotNull
        Short businessUnitId,

        @NotNull
        Integer contoCoge,

        Long categoriaId,
        UUID fornitoreId,
        UUID eventoId,
        String tipoEventoMovimento,

        @NotBlank
        String descrizione,

        String note,
        String riferimentoEsterno,
        String fonte,
        String allegatoPath
) {}

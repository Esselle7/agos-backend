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

        /** Alias economico, auto-impostato = dataMovimento dal backend se null. */
        LocalDate dataCompetenza,

        /**
         * Data di liquidazione effettiva (quando i soldi entrano/escono dal conto).
         * null = movimento DA_LIQUIDARE; valorizzata = REGISTRATO (liquidato).
         * Quando valorizzata: contoBancarioId e metodoPagamentoId diventano obbligatori
         * e dataLiquidita viene auto-impostata uguale a dataFinanziaria.
         */
        LocalDate dataFinanziaria,

        /**
         * Scadenza finanziaria attesa (informativa).
         * Obbligatoria quando dataFinanziaria è null.
         * Auto-impostata = dataFinanziaria quando il movimento è liquidato.
         */
        LocalDate dataLiquidita,

        Short contoBancarioId,

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

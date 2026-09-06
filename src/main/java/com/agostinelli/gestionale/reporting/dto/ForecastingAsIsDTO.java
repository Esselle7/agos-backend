package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;

public record ForecastingAsIsDTO(
        BigDecimal saldoLiquidita,
        BigDecimal ricaviYtd,
        BigDecimal costiYtd,
        BigDecimal ebitdaYtd,
        BigDecimal creditiAperti,
        BigDecimal debitiAperti,
        /**
         * Credito da eventi GIÀ CELEBRATI: ricavo maturato (riga COMPETENZA creata da
         * EventiService) e non ancora incassato. Sottoinsieme di {@code creditiAperti} — stessa
         * query, con in più {@code evento_id IS NOT NULL}.
         *
         * P4 di docs/specs/previsionale-correzioni.md: sta FUORI dalla proiezione di cassa e non
         * va mai sommato al saldo finale. Non ha una data attesa di incasso e non se ne inventa
         * una: parte di questo credito è con ogni probabilità denaro già in banca e non ancora
         * importato (misurato il 06/09/2026 — «Matrimonio roberta» 09/07: 3.600,00 preventivati,
         * 0,00 incassati).
         *
         * Campo in coda per G8 (additivo).
         */
        BigDecimal creditoEventiCelebrati,
        /** Data dell'ultimo movimento con cassa a libro: {@code MAX(data_finanziaria)}. Null se non
         *  ce n'è nessuno. */
        java.time.LocalDate ultimaDataCassa,
        /** true se {@code ultimaDataCassa} è più vecchia di {@code forecast.freschezza.giorni-max}
         *  (o assente). Un previsionale su dati fermi va dichiarato, non presentato come fresco. */
        boolean datiIncompleti,
        /** Testo che dichiara la data e i mesi scoperti. Valorizzato solo se {@code datiIncompleti}
         *  — stesso pattern di {@code ReportingService.computeQualita}. */
        String nota) {}

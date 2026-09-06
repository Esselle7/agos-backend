package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.util.List;

public record ForecastingEconomicoDTO(
        BigDecimal ricaviPrevisti,
        BigDecimal costiPrevisti,
        BigDecimal ebitdaPrevisto,
        /** Quota di ammortamento cespiti attesa nel periodo (tra EBITDA ed EBIT). */
        BigDecimal ammortamentiPrevisti,
        BigDecimal oneriFinanziariPrevisti,
        BigDecimal ebitPrevisto,
        List<ForecastingDettaglioDTO> dettaglio,
        /**
         * EBT previsto = ebitPrevisto − oneriFinanziariPrevisti (P3 di
         * docs/specs/previsionale-correzioni.md). Si ferma qui: niente imposte e niente utile
         * netto, il previsionale non ha una previsione fiscale e inventarne una sarebbe peggio
         * che non darla.
         *
         * Campo in coda per G8 (additivo). L'ordine dei componenti di un record non è
         * osservabile nel JSON, che è a chiavi: nessun client rompe.
         */
        BigDecimal ebtPrevisto,
        /**
         * Perché la stima dei costi non è disponibile, o null se lo è (P7).
         *
         * Il gate di sufficienza dati è fail-closed e fa parte della feature: una media su un mese
         * solo non è una stima, è la copia di quel mese. Ma il silenzio non basta — se la stima non
         * c'è, la pagina deve dire perché, altrimenti l'utente legge «costi previsti 0,00» e crede
         * che l'azienda non spenda.
         */
        String notaStimaCosti) {}

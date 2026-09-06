package com.agostinelli.gestionale.reporting;

import java.time.LocalDate;

/**
 * Confini temporali del gestionale, in un posto solo.
 *
 * Nasce da P6 di docs/specs/previsionale-correzioni.md: {@code GO_LIVE} era dichiarato due volte
 * (ReportingService ed EventiService) e stava per diventare tre. È un accorpamento, non un
 * comportamento nuovo — il valore è lo stesso che avevano entrambe le copie.
 */
public final class Perimetro {

    /**
     * Data di apertura del gestionale. Prima di questa data non esiste un conto economico: ci sono
     * solo i saldi iniziali al 30/06 e gli eventi a storico, che non generano né competenza né
     * previsione.
     */
    public static final LocalDate GO_LIVE = LocalDate.of(2026, 7, 1);

    private Perimetro() {}
}

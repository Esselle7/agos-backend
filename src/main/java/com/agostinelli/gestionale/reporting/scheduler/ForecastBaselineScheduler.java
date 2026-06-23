package com.agostinelli.gestionale.reporting.scheduler;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Ricalcolo notturno della {@code forecast_baseline} (layer STIMATO del previsionale).
 *
 * <p>Riusa il pattern di {@link MvRefreshScheduler}: try/catch + log, tollerante a Neon in sleep
 * (su free tier il cold-start può far fallire il primo tentativo). Il job è idempotente
 * (DELETE+INSERT) → un'esecuzione persa la notte non rompe nulla, la successiva riallinea.</p>
 */
@ApplicationScoped
public class ForecastBaselineScheduler {

    private static final Logger log = Logger.getLogger(ForecastBaselineScheduler.class);

    @Inject
    ForecastBaselineService service;

    @Scheduled(cron = "${forecast.baseline.cron:0 0 3 * * ?}")
    void recomputeNightly() {
        try {
            int n = service.recompute();
            log.infof("Forecast baseline ricalcolata: %d segmenti", n);
        } catch (Exception e) {
            log.warnf("Ricalcolo forecast baseline fallito (Neon potrebbe essere in sleep): %s", e.getMessage());
        }
    }

    /** Backfill allo startup se la tabella è vuota: la pagina Previsioni ha subito una baseline. */
    void onStart(@Observes StartupEvent ev) {
        try {
            if (service.isEmpty()) {
                int n = service.recompute();
                log.infof("Forecast baseline: backfill startup, %d segmenti", n);
            }
        } catch (Exception e) {
            log.warnf("Backfill forecast baseline allo startup fallito: %s", e.getMessage());
        }
    }
}

package com.agostinelli.gestionale.reporting.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Fallback periodico: anche se il refresh on-write (vedi {@link MvRefreshService})
 * coprisse tutte le mutazioni, lo scheduler garantisce un baseline minimo di
 * freschezza dei dati in caso di anomalie (executor terminato, exception
 * silenziata, ecc.).
 */
@ApplicationScoped
public class MvRefreshScheduler {

    private static final Logger log = Logger.getLogger(MvRefreshScheduler.class);

    @Inject
    MvRefreshRunner runner;

    // WHY intervallo configurabile: su Neon free tier il cold-start può allungare il refresh;
    // permettere di aumentare l'intervallo via env var senza deploy.
    @Scheduled(every = "${reporting.mv.refresh-interval:30m}")
    void refreshMaterializedViews() {
        try {
            long start = System.currentTimeMillis();
            runner.refresh();
            log.infof("MV refresh schedulato completato in %dms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warnf("MV refresh schedulato fallito (Neon potrebbe essere in sleep): %s", e.getMessage());
        }
    }
}

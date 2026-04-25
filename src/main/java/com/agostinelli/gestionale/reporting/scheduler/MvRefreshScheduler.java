package com.agostinelli.gestionale.reporting.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MvRefreshScheduler {

    private static final Logger log = Logger.getLogger(MvRefreshScheduler.class);

    @Inject
    EntityManager em;

    // WHY intervallo configurabile: su Neon free tier il cold-start può allungare il refresh;
    // permettere di aumentare l'intervallo via env var senza deploy.
    @Scheduled(every = "${reporting.mv.refresh-interval:30m}")
    @Transactional
    void refreshMaterializedViews() {
        try {
            long start = System.currentTimeMillis();
            em.createNativeQuery("SELECT fn_refresh_all_mv()").getSingleResult();
            log.infof("MV refresh completato in %dms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warnf("MV refresh fallito (Neon potrebbe essere in sleep): %s", e.getMessage());
        }
    }
}

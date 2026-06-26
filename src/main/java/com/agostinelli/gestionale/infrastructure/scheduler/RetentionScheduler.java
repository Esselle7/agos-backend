package com.agostinelli.gestionale.infrastructure.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.IntSupplier;

/**
 * Data-retention notturna: tiene leggero il DB cancellando log/staging vecchi.
 * Tabelle contabili (movimenti, eventi, anagrafiche) NON vengono mai toccate.
 *
 * Schedulazione = Quarkus @Scheduled (Opzione A): nessuna dipendenza da pg_cron
 * (non installato nell'immagine Postgres). Il lavoro transazionale è in {@link RetentionRunner}.
 */
@ApplicationScoped
public class RetentionScheduler {

    private static final Logger log = Logger.getLogger(RetentionScheduler.class);

    @Inject
    RetentionRunner runner;

    /** Ogni notte alle 03:00 (poca attività, prima del refresh MV). */
    @Scheduled(cron = "${retention.cron:0 0 3 * * ?}")
    void cleanup() {
        run("audit_log", runner::purgeAudit);
        run("import_ambiguita", runner::purgeAmbiguitaClassificate);
        run("import_scartati", runner::purgeScartati);
        run("import_log (orfani)", runner::purgeImportLogOrfani);
    }

    private void run(String tabella, IntSupplier op) {
        try {
            log.infof("Retention %s: cancellate %d righe", tabella, op.getAsInt());
        } catch (Exception e) {
            log.warnf("Retention %s fallita: %s", tabella, e.getMessage());
        }
    }
}

package com.agostinelli.gestionale.spese.scheduler;

import com.agostinelli.gestionale.spese.service.RecurringExpenseService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RecurringExpenseScheduler {

    private static final Logger log = Logger.getLogger(RecurringExpenseScheduler.class);

    @Inject RecurringExpenseService service;

    // ogni giorno alle 06:00 – processa le rate scadute
    @Scheduled(cron = "0 0 6 * * ?")
    void processInstallments() {
        try {
            int processed = service.processScheduledInstallments();
            if (processed > 0) {
                log.infof("Spese ricorrenti: %d rate convertite in movimenti", processed);
            }
        } catch (Exception e) {
            log.errorf("Errore elaborazione spese ricorrenti: %s", e.getMessage());
        }
    }
}

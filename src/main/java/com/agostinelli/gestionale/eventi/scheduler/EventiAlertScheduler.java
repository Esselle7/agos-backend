package com.agostinelli.gestionale.eventi.scheduler;

import com.agostinelli.gestionale.eventi.domain.Evento;
import com.agostinelli.gestionale.eventi.repository.EventiRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Scheduler giornaliero che controlla le anomalie economiche sugli eventi
 * e le segnala via log strutturato (con ID evento per tracciabilità).
 *
 * MVP: solo log WARN.
 * TODO [evolutiva]: integrare servizio email per notificare ADMIN e responsabili commerciali
 *   - template email con lista eventi + link diretto alla scheda evento
 *   - destinatari configurabili via application.properties (alert.email.recipients)
 *   - throttling per evitare spam (max 1 email/giorno per evento)
 */
@ApplicationScoped
public class EventiAlertScheduler {

    private static final Logger LOG = Logger.getLogger(EventiAlertScheduler.class);

    @Inject EventiRepository repo;

    /**
     * Eseguito ogni giorno alle 08:00.
     * Controlla eventi CONFERMATI senza caparra nei prossimi 30 giorni
     * ed eventi SALDATI con residuo positivo (anomalia dati).
     */
    @Scheduled(cron = "0 0 8 * * ?")
    @Transactional
    void checkEventiAlert() {
        checkCaparreMancantiEntroDays(30);
        checkSaldatiConResiduoPositivo();
    }

    private void checkCaparreMancantiEntroDays(int giorni) {
        List<Evento> eventi = repo.findEventiConCaparraMancanteEntroDays(giorni);
        for (Evento e : eventi) {
            LOG.warnf("ALERT: Evento [%s] id=%s del %s CONFERMATO senza caparra incassata",
                    e.nome, e.id, e.dataEvento);
            // TODO [evolutiva]: inviare email di alert al responsabile commerciale
        }
    }

    private void checkSaldatiConResiduoPositivo() {
        List<Evento> eventi = repo.findSaldatiConResiduoPositivo();
        for (Evento e : eventi) {
            BigDecimal residuo = e.importoTotalePreviventivato.subtract(e.importoIncassato);
            LOG.warnf("ALERT: Evento [%s] id=%s SALDATO con residuo EUR %s ancora da incassare (anomalia)",
                    e.nome, e.id, residuo);
            // TODO [evolutiva]: inviare email di alert all'amministrazione
        }
    }
}

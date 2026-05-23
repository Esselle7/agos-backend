package com.agostinelli.gestionale.reporting.scheduler;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordina il refresh asincrono delle materialized view dopo le scritture
 * (movimenti, pagamenti evento, rate piano ricorrente).
 *
 * Pattern di funzionamento:
 *  1) Il chiamante invoca {@link #requestRefreshAfterCommit()} all'interno della
 *     transazione di scrittura. Il metodo fa fire(...) di un evento CDI.
 *  2) L'observer {@link #onAfterCommit} è registrato con
 *     {@link TransactionPhase#AFTER_SUCCESS}: si attiva SOLO dopo il commit della
 *     transazione del chiamante. Se la transazione fa rollback, nessun refresh.
 *  3) L'observer dispatcha il lavoro su un executor a thread singolo (esce
 *     immediatamente dal thread della request → l'utente vede la risposta HTTP
 *     subito).
 *  4) Il worker applica un piccolo debounce (200ms) e coalesce le richieste:
 *     una raffica di scritture in burst produce 1 solo REFRESH. Scritture che
 *     arrivano dopo l'inizio del REFRESH ne accodano uno successivo.
 *
 * Sicurezza concorrenza con lo scheduler periodico: PostgreSQL serializza
 * REFRESH MATERIALIZED VIEW CONCURRENTLY sulla stessa MV; due refresh
 * contemporanei vengono accodati senza errori.
 */
@ApplicationScoped
public class MvRefreshService {

    private static final Logger log = Logger.getLogger(MvRefreshService.class);

    @Inject
    MvRefreshRunner runner;

    @Inject
    Event<MvWriteEvent> event;

    /**
     * Debounce per coalescere bursts: scritture multiple ravvicinate condividono
     * un solo REFRESH. Configurabile via {@code reporting.mv.async-debounce-ms}.
     */
    @ConfigProperty(name = "reporting.mv.async-debounce-ms", defaultValue = "200")
    long debounceMs;

    /**
     * Se false, {@link #requestRefreshAfterCommit()} è un no-op. Utile per
     * test/CI dove non si vuole rumore extra di REFRESH durante 405 chiamate.
     */
    @ConfigProperty(name = "reporting.mv.async-on-write", defaultValue = "true")
    boolean enabled;

    private ExecutorService executor;
    private final AtomicBoolean queued = new AtomicBoolean(false);

    void onStart(@Observes StartupEvent ev) {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mv-refresh-async");
            t.setDaemon(true);
            return t;
        });
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * Chiamato dalle service che hanno appena scritto su movimenti/eventi/spese.
     * Il refresh effettivo parte SOLO se la transazione corrente commit-a.
     */
    public void requestRefreshAfterCommit() {
        if (!enabled) return;
        event.fire(new MvWriteEvent());
    }

    void onAfterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) MvWriteEvent ev) {
        scheduleAsync();
    }

    private void scheduleAsync() {
        if (executor == null) return;
        if (!queued.compareAndSet(false, true)) {
            // un refresh è già accodato → questa scrittura verrà coperta da quello
            return;
        }
        executor.submit(() -> {
            try {
                if (debounceMs > 0) Thread.sleep(debounceMs);
                // Sblocca PRIMA del refresh: scritture che arrivano durante il
                // REFRESH dovranno accodare un altro refresh per essere visibili.
                queued.set(false);
                long start = System.currentTimeMillis();
                runner.refresh();
                log.debugf("MV refresh asincrono completato in %dms", System.currentTimeMillis() - start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                queued.set(false);
            } catch (Exception e) {
                log.warnf("MV refresh asincrono fallito: %s", e.getMessage());
                queued.set(false);
            }
        });
    }

    /** Evento CDI interno usato per agganciarsi al commit di transazione. */
    public static final class MvWriteEvent {
        private MvWriteEvent() {}
    }
}

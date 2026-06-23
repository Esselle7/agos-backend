package com.agostinelli.gestionale.reporting.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Ricalcola {@code forecast_baseline}: la media mobile per giorno-della-settimana dei soli ricavi
 * cash ad alta frequenza (cassa ristorante, carne, ortofrutta). È il layer STIMATO del previsionale,
 * distinto dal CERTO (eventi/rate/stipendi/movimenti da liquidare).
 *
 * <p>Eseguito di notte (vedi {@link ForecastBaselineScheduler}) e in backfill allo startup. La pagina
 * Previsioni legge solo la tabella → costo a runtime nullo.</p>
 *
 * <p><b>Allowlist deliberata</b> = SOLO i 3 conti ricavo cash. Esclusi di proposito: alveare/shopify
 * (payout lumpy), materie prime/food cost (lumpy + già nel certo come movimenti da liquidare →
 * doppio conteggio). Restringere lo scope ai soli ricavi cash è ciò che tiene il sistema semplice.</p>
 */
@ApplicationScoped
public class ForecastBaselineService {

    /** Conti ricavo cash ad alta frequenza (codici stabili nel piano dei conti, vedi seed V4). */
    static final List<String> CONTI_RICAVO_CASH = List.of("30.01.001", "30.03.001", "30.03.002");

    @Inject
    EntityManager em;

    @ConfigProperty(name = "forecast.baseline.finestra-settimane", defaultValue = "8")
    int finestraSettimane;

    /**
     * Ricalcola la baseline da zero sulla finestra mobile. DELETE+INSERT (non solo UPSERT) perché la
     * finestra che scorre può far sparire un segmento (un dow senza più movimenti recenti): l'upsert
     * da solo lascerebbe la riga vecchia, sovrastimando giorni ormai senza dati.
     *
     * <p>media_attesa = incasso medio GIORNALIERO per quel dow = SUM(lordo)/giorni_distinti (non
     * AVG per-riga: più scontrini lo stesso giorno devono contare come un giorno solo, coerente con
     * la proiezione che somma una media per ogni giorno futuro).</p>
     *
     * @return numero di segmenti (conto, dow) calcolati
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int recompute() {
        em.createNativeQuery("DELETE FROM forecast_baseline").executeUpdate();

        return em.createNativeQuery(
                "INSERT INTO forecast_baseline (conto_coge_id, dow, media_attesa, n_giorni, updated_at) " +
                "SELECT m.conto_coge_id, " +
                "       CAST(EXTRACT(DOW FROM m.data_movimento) AS smallint) AS dow, " +
                "       CAST(SUM(m.importo_lordo) / COUNT(DISTINCT m.data_movimento) AS numeric(12,2)) AS media_attesa, " +
                "       COUNT(DISTINCT m.data_movimento) AS n_giorni, " +
                "       now() " +
                "FROM movimenti m " +
                "WHERE m.conto_coge_id IN (" +
                "        SELECT id FROM piano_dei_conti_coge WHERE codice IN (:conti)) " +
                "  AND m.tipo = 'ENTRATA' " +
                "  AND m.evento_id IS NULL " +
                "  AND m.stato <> 'ANNULLATO' " +
                "  AND m.data_movimento >= current_date - make_interval(weeks => :settimane) " +
                "GROUP BY m.conto_coge_id, EXTRACT(DOW FROM m.data_movimento)")
                .setParameter("conti", CONTI_RICAVO_CASH)
                .setParameter("settimane", finestraSettimane)
                .executeUpdate();
    }

    /** True se la tabella è vuota (usato per decidere il backfill allo startup). */
    public boolean isEmpty() {
        Number n = (Number) em.createNativeQuery("SELECT COUNT(*) FROM forecast_baseline").getSingleResult();
        return n.longValue() == 0;
    }
}

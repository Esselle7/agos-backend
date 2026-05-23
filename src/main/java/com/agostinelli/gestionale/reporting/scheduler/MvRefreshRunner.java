package com.agostinelli.gestionale.reporting.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Esecutore atomico del refresh delle materialized view P&L / CF / KPI.
 * Separato da {@link MvRefreshScheduler} e da {@link MvRefreshService} per
 * poter essere invocato da:
 *  1) lo scheduler periodico (fallback ogni N minuti);
 *  2) il path asincrono on-write, che gira fuori dal thread della request.
 *
 * Il metodo usa {@link Transactional.TxType#REQUIRES_NEW} perché:
 *  - quando chiamato dallo scheduler non c'è una transazione attiva;
 *  - quando chiamato dal worker async post-commit, la transazione del chiamante
 *    è già terminata e questa partenza fresca evita di legare REFRESH a stato
 *    di trasferimento dati.
 */
@ApplicationScoped
public class MvRefreshRunner {

    @Inject
    EntityManager em;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void refresh() {
        em.createNativeQuery("SELECT fn_refresh_all_mv()").getSingleResult();
    }
}

package com.agostinelli.gestionale.movimenti.importlayer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unica sorgente dei piani ricorrenti confrontabili con l'import (DRY): la usano sia il routing
 * dell'import (parcheggia invece di contabilizzare) sia la lettura della coda (proposta a un click).
 *
 * <p>Solo piani <b>ATTIVO</b> e rate <b>PENDING</b>: un piano completato/annullato o una rata già
 * pagata non sono mai candidati (SPEC R7).
 */
@ApplicationScoped
public class RataMatchService {

    @Inject EntityManager em;

    private static final String SQL = """
            SELECT p.id, p.descrizione, p.riferimento_estratto_conto, p.conto_bancario_id, p.importo_rata,
                   i.id, i.numero_rata, i.data_scadenza, i.importo, c.codice
            FROM recurring_expense_plan p
            JOIN piano_dei_conti_coge c ON c.id = p.conto_coge_id
            LEFT JOIN recurring_expense_installment i ON i.piano_id = p.id AND i.stato = 'PENDING'
            WHERE p.stato = 'ATTIVO'
            ORDER BY p.id, i.numero_rata
            """;

    /**
     * Piani attivi con le loro rate da pagare.
     * ponytail: una query per riga d'import (≈100 righe/mese su una tabella da decine di piani).
     * Se il volume cresce, caricarli una volta per transazione d'import e passarli al gate.
     */
    @SuppressWarnings("unchecked")
    public List<RataMatcher.Piano> pianiAttivi() {
        List<Object[]> rows = em.createNativeQuery(SQL).getResultList();
        List<RataMatcher.Piano> piani = new ArrayList<>();
        UUID corrente = null;
        List<RataMatcher.Rata> rate = null;
        Object[] testa = null;
        for (Object[] r : rows) {
            UUID pianoId = uuid(r[0]);
            if (!pianoId.equals(corrente)) {
                if (testa != null) piani.add(piano(testa, rate));
                corrente = pianoId;
                testa = r;
                rate = new ArrayList<>();
            }
            if (r[5] != null) {
                rate.add(new RataMatcher.Rata(uuid(r[5]), ((Number) r[6]).intValue(),
                        ((java.sql.Date) r[7]).toLocalDate(), (BigDecimal) r[8]));
            }
        }
        if (testa != null) piani.add(piano(testa, rate));
        return piani;
    }

    private static RataMatcher.Piano piano(Object[] r, List<RataMatcher.Rata> rate) {
        return new RataMatcher.Piano(uuid(r[0]), (String) r[1], (String) r[2],
                r[3] == null ? null : ((Number) r[3]).shortValue(), (String) r[9],
                (BigDecimal) r[4], List.copyOf(rate));
    }

    private static UUID uuid(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(String.valueOf(o));
    }
}

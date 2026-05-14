package com.agostinelli.gestionale.spese.repository;

import com.agostinelli.gestionale.spese.domain.RecurringExpenseInstallment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RecurringExpenseInstallmentRepository
        implements PanacheRepositoryBase<RecurringExpenseInstallment, UUID> {

    public List<RecurringExpenseInstallment> findByPianoOrdered(UUID pianoId) {
        return list("pianoId = ?1 ORDER BY numeroRata ASC", pianoId);
    }

    public List<RecurringExpenseInstallment> findPendingDue(LocalDate upTo) {
        return list("stato = 'PENDING' AND dataScadenza <= ?1", upTo);
    }

    public List<RecurringExpenseInstallment> findPendingByPiano(UUID pianoId) {
        return list("pianoId = ?1 AND stato = 'PENDING' ORDER BY numeroRata ASC", pianoId);
    }

    public Optional<RecurringExpenseInstallment> findNextPendingAfter(UUID pianoId, int afterNumero) {
        return find("pianoId = ?1 AND stato = 'PENDING' AND numeroRata > ?2 ORDER BY numeroRata ASC",
                pianoId, afterNumero)
                .firstResultOptional();
    }

    public int maxNumeroRata(UUID pianoId) {
        Number max = (Number) getEntityManager()
                .createQuery("SELECT COALESCE(MAX(r.numeroRata), 0) FROM RecurringExpenseInstallment r WHERE r.pianoId = :pid")
                .setParameter("pid", pianoId)
                .getSingleResult();
        return max.intValue();
    }
}

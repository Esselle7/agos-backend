package com.agostinelli.gestionale.cassa.repository;

import com.agostinelli.gestionale.cassa.domain.CassaMovimento;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CassaMovimentiRepository implements PanacheRepositoryBase<CassaMovimento, UUID> {

    @Inject
    EntityManager em;

    public List<CassaMovimento> findByPeriodo(LocalDate from, LocalDate to, int page, int size) {
        StringBuilder jpql = new StringBuilder("FROM CassaMovimento m WHERE m.stato != 'ANNULLATO'");
        if (from != null) jpql.append(" AND m.dataMovimento >= :from");
        if (to != null)   jpql.append(" AND m.dataMovimento <= :to");
        jpql.append(" ORDER BY m.dataMovimento DESC");

        var q = em.createQuery(jpql.toString(), CassaMovimento.class)
                .setFirstResult(page * size)
                .setMaxResults(size);
        if (from != null) q.setParameter("from", from);
        if (to != null)   q.setParameter("to", to);
        return q.getResultList();
    }

    public long countByPeriodo(LocalDate from, LocalDate to) {
        StringBuilder jpql = new StringBuilder("SELECT COUNT(m) FROM CassaMovimento m WHERE m.stato != 'ANNULLATO'");
        if (from != null) jpql.append(" AND m.dataMovimento >= :from");
        if (to != null)   jpql.append(" AND m.dataMovimento <= :to");

        var q = em.createQuery(jpql.toString(), Long.class);
        if (from != null) q.setParameter("from", from);
        if (to != null)   q.setParameter("to", to);
        return q.getSingleResult();
    }

    /**
     * Saldo corrente: ENTRATA e VERSAMENTO_IN_BANCA aumentano la cassa,
     * USCITA e PRELIEVO_DA_BANCA la diminuiscono.
     */
    public BigDecimal calcolaSaldo() {
        Object result = em.createQuery(
                "SELECT SUM(CASE WHEN m.tipo IN ('ENTRATA', 'VERSAMENTO_IN_BANCA') " +
                "  THEN m.importo ELSE -m.importo END) " +
                "FROM CassaMovimento m WHERE m.stato != 'ANNULLATO'")
                .getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }

    public LocalDate dataUltimoMovimento() {
        Object result = em.createQuery(
                "SELECT MAX(m.dataMovimento) FROM CassaMovimento m WHERE m.stato != 'ANNULLATO'")
                .getSingleResult();
        return result != null ? (LocalDate) result : LocalDate.now();
    }
}

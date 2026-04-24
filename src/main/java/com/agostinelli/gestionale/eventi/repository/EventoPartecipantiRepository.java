package com.agostinelli.gestionale.eventi.repository;

import com.agostinelli.gestionale.eventi.domain.EventoPartecipante;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class EventoPartecipantiRepository implements PanacheRepositoryBase<EventoPartecipante, Long> {

    @Inject
    EntityManager em;

    public List<EventoPartecipante> findByEventoId(UUID eventoId) {
        return list("eventoId = ?1", eventoId);
    }

    public boolean existsByEventoIdAndPersonaleId(UUID eventoId, UUID personaleId) {
        return count("eventoId = ?1 AND personaleId = ?2", eventoId, personaleId) > 0;
    }

    /** Somma dei costi dei partecipanti assegnati all'evento. */
    public BigDecimal sumCostiByEventoId(UUID eventoId) {
        Object result = em.createQuery(
                        "SELECT SUM(p.costo) FROM EventoPartecipante p " +
                        "WHERE p.eventoId = :eid AND p.costo IS NOT NULL")
                .setParameter("eid", eventoId)
                .getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }
}

package com.agostinelli.gestionale.eventi.repository;

import com.agostinelli.gestionale.eventi.domain.Evento;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class EventiRepository implements PanacheRepositoryBase<Evento, UUID> {

    @Inject
    EntityManager em;

    public List<Evento> findWithFilters(
            String stato, Short buId, LocalDate from, LocalDate to, String search,
            int page, int size) {

        StringBuilder jpql = new StringBuilder("FROM Evento e WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(jpql, params, stato, buId, from, to, search);
        jpql.append(" ORDER BY e.dataEvento DESC");

        TypedQuery<Evento> q = em.createQuery(jpql.toString(), Evento.class)
                .setFirstResult(page * size)
                .setMaxResults(size);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    public long countWithFilters(
            String stato, Short buId, LocalDate from, LocalDate to, String search) {

        StringBuilder jpql = new StringBuilder("SELECT COUNT(e) FROM Evento e WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(jpql, params, stato, buId, from, to, search);

        TypedQuery<Long> q = em.createQuery(jpql.toString(), Long.class);
        params.forEach(q::setParameter);
        return q.getSingleResult();
    }

    private void appendFilters(StringBuilder jpql, Map<String, Object> params,
            String stato, Short buId, LocalDate from, LocalDate to, String search) {

        if (stato != null) {
            jpql.append(" AND e.stato = :stato");
            params.put("stato", stato);
        }
        if (buId != null) {
            jpql.append(" AND e.businessUnitId = :buId");
            params.put("buId", buId);
        }
        if (from != null) {
            jpql.append(" AND e.dataEvento >= :from");
            params.put("from", from);
        }
        if (to != null) {
            jpql.append(" AND e.dataEvento <= :to");
            params.put("to", to);
        }
        if (search != null && !search.isBlank()) {
            jpql.append(" AND (LOWER(e.nome) LIKE :search OR LOWER(e.contattoNome) LIKE :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
    }

    /** CONFERMATI senza caparra con data evento nei prossimi {@code giorni} giorni. */
    public List<Evento> findEventiConCaparraMancanteEntroDays(int giorni) {
        LocalDate oggi  = LocalDate.now();
        LocalDate limite = oggi.plusDays(giorni);
        return list(
                "stato = 'CONFERMATO' AND caparreIncassate = 0 AND dataEvento >= ?1 AND dataEvento <= ?2",
                oggi, limite);
    }

    /** SALDATI con residuo ancora positivo (anomalia scheduler). */
    public List<Evento> findSaldatiConResiduoPositivo() {
        return em.createQuery(
                "FROM Evento e WHERE e.stato = 'SALDATO' " +
                "AND e.importoTotalePreviventivato IS NOT NULL " +
                "AND (e.importoTotalePreviventivato - e.importoIncassato) > 0.01",
                Evento.class).getResultList();
    }

    public List<Evento> findCalendario(LocalDate from, LocalDate to) {
        return list("dataEvento >= ?1 AND dataEvento <= ?2 ORDER BY dataEvento ASC", from, to);
    }

    /**
     * Restituisce gli eventi a cui il personale è assegnato come partecipante.
     * Usata dall'endpoint {@code GET /api/eventi/miei}.
     */
    public List<Evento> findByPersonaleId(UUID personaleId, int page, int size) {
        return em.createQuery(
                "FROM Evento e WHERE e.id IN " +
                "(SELECT ep.eventoId FROM EventoPartecipante ep WHERE ep.personaleId = :pid) " +
                "ORDER BY e.dataEvento DESC", Evento.class)
                .setParameter("pid", personaleId)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
    }

    public long countByPersonaleId(UUID personaleId) {
        return (Long) em.createQuery(
                "SELECT COUNT(e) FROM Evento e WHERE e.id IN " +
                "(SELECT ep.eventoId FROM EventoPartecipante ep WHERE ep.personaleId = :pid)")
                .setParameter("pid", personaleId)
                .getSingleResult();
    }
}

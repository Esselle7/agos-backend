package com.agostinelli.gestionale.personale.repository;

import com.agostinelli.gestionale.personale.domain.Personale;
import com.agostinelli.gestionale.personale.dto.PersonaleSummaryDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PersonaleRepository implements PanacheRepositoryBase<Personale, UUID> {

    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    public List<PersonaleSummaryDTO> search(String query, Short buId, String mansione, Boolean activeOnly, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT CAST(p.id AS text), p.nome, p.cognome,
                       CAST(p.mansione_id AS text), m.nome as mansione_nome,
                       p.business_unit_id, b.nome as bu_nome,
                       p.costo_aziendale_mensile, p.is_active
                FROM personale p
                LEFT JOIN business_units b ON b.id = p.business_unit_id
                LEFT JOIN mansioni m ON m.id = p.mansione_id
                WHERE 1=1
                """);
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(p.nome) LIKE :q OR LOWER(p.cognome) LIKE :q OR LOWER(COALESCE(m.nome,'')) LIKE :q)");
        }
        if (buId != null) {
            sql.append(" AND p.business_unit_id = :buId");
        }
        if (mansione != null && !mansione.isBlank()) {
            sql.append(" AND LOWER(m.nome) = LOWER(:mansione)");
        }
        if (Boolean.TRUE.equals(activeOnly)) {
            sql.append(" AND p.is_active = true");
        }
        sql.append(" ORDER BY p.cognome, p.nome LIMIT :size OFFSET :offset");

        var nq = em.createNativeQuery(sql.toString());
        if (query != null && !query.isBlank()) nq.setParameter("q", "%" + query.toLowerCase() + "%");
        if (buId != null) nq.setParameter("buId", buId);
        if (mansione != null && !mansione.isBlank()) nq.setParameter("mansione", mansione);
        nq.setParameter("size", size);
        nq.setParameter("offset", page * size);

        List<Object[]> rows = nq.getResultList();
        return rows.stream().map(r -> new PersonaleSummaryDTO(
                UUID.fromString((String) r[0]),
                (String) r[1],
                (String) r[2],
                r[3] != null ? UUID.fromString((String) r[3]) : null,
                (String) r[4],
                r[5] != null ? ((Number) r[5]).shortValue() : null,
                (String) r[6],
                (BigDecimal) r[7],
                (Boolean) r[8]
        )).toList();
    }

    public long countSearch(String query, Short buId, String mansione, Boolean activeOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM personale p
                LEFT JOIN mansioni m ON m.id = p.mansione_id
                WHERE 1=1
                """);
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(p.nome) LIKE :q OR LOWER(p.cognome) LIKE :q OR LOWER(COALESCE(m.nome,'')) LIKE :q)");
        }
        if (buId != null) sql.append(" AND p.business_unit_id = :buId");
        if (mansione != null && !mansione.isBlank()) sql.append(" AND LOWER(m.nome) = LOWER(:mansione)");
        if (Boolean.TRUE.equals(activeOnly)) sql.append(" AND p.is_active = true");

        var nq = em.createNativeQuery(sql.toString());
        if (query != null && !query.isBlank()) nq.setParameter("q", "%" + query.toLowerCase() + "%");
        if (buId != null) nq.setParameter("buId", buId);
        if (mansione != null && !mansione.isBlank()) nq.setParameter("mansione", mansione);

        return ((Number) nq.getSingleResult()).longValue();
    }
}

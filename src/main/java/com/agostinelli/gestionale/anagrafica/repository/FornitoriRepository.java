package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.Fornitore;
import com.agostinelli.gestionale.anagrafica.dto.FornitoreSummaryDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class FornitoriRepository implements PanacheRepositoryBase<Fornitore, UUID> {

    private static final int MAX_PAGE_SIZE = 100;

    @Inject
    EntityManager em;

    public List<FornitoreSummaryDTO> searchFullText(String query, int page, int size) {
        int safeSize = Math.min(size, MAX_PAGE_SIZE);
        String pattern = "%" + query.toLowerCase() + "%";
        return em.createQuery("""
                SELECT new com.agostinelli.gestionale.anagrafica.dto.FornitoreSummaryDTO(f.id, f.ragioneSociale)
                FROM Fornitore f
                WHERE LOWER(f.ragioneSociale) LIKE :pattern
                   OR LOWER(f.alias) LIKE :pattern
                ORDER BY f.ragioneSociale
                """, FornitoreSummaryDTO.class)
                .setParameter("pattern", pattern)
                .setFirstResult(page * safeSize)
                .setMaxResults(safeSize)
                .getResultList();
    }

    public long countFullText(String query) {
        String pattern = "%" + query.toLowerCase() + "%";
        return em.createQuery("""
                SELECT COUNT(f) FROM Fornitore f
                WHERE LOWER(f.ragioneSociale) LIKE :pattern
                   OR LOWER(f.alias) LIKE :pattern
                """, Long.class)
                .setParameter("pattern", pattern)
                .getSingleResult();
    }

    // Usato dall'import: cerca il fornitore il cui alias pattern matcha il testo della causale.
    // Richiede query nativa per la logica condizionale match_type (CONTAINS/STARTS_WITH/REGEX).
    @SuppressWarnings("unchecked")
    public Optional<UUID> findUUIDByPattern(String text) {
        List<Object> results = em.createNativeQuery("""
                SELECT f.id::text FROM fornitori f
                JOIN fornitore_alias_matching fam ON fam.fornitore_id = f.id
                WHERE
                    (fam.match_type = 'CONTAINS'    AND :text ILIKE '%' || fam.pattern || '%')
                    OR (fam.match_type = 'STARTS_WITH' AND :text ILIKE fam.pattern || '%')
                    OR (fam.match_type = 'REGEX'       AND :text ~ fam.pattern)
                LIMIT 1
                """)
                .setParameter("text", text)
                .getResultList();

        return results.isEmpty() ? Optional.empty()
                : Optional.of(UUID.fromString((String) results.get(0)));
    }
}

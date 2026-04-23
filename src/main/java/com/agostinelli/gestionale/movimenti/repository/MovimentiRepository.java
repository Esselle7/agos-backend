package com.agostinelli.gestionale.movimenti.repository;

import com.agostinelli.gestionale.movimenti.domain.Movimento;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class MovimentiRepository implements PanacheRepositoryBase<Movimento, UUID> {

    @Inject
    EntityManager em;

    /**
     * Query dinamica con tutti i filtri opzionali.
     * I parametri null vengono ignorati per non vincolare la query.
     */
    public List<Movimento> findWithFilters(
            String tipo, Short buId, Long categoriaId, Integer metodoPagamentoId,
            String stato, UUID fornitoreId, UUID eventoId,
            LocalDate from, LocalDate to, String search,
            int page, int size, String sort) {

        StringBuilder jpql = new StringBuilder("FROM Movimento m WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        appendFilters(jpql, params, tipo, buId, categoriaId, metodoPagamentoId,
                stato, fornitoreId, eventoId, from, to, search);
        jpql.append(buildSort(sort));

        TypedQuery<Movimento> q = em.createQuery(jpql.toString(), Movimento.class)
                .setFirstResult(page * size)
                .setMaxResults(size);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    public long countWithFilters(
            String tipo, Short buId, Long categoriaId, Integer metodoPagamentoId,
            String stato, UUID fornitoreId, UUID eventoId,
            LocalDate from, LocalDate to, String search) {

        StringBuilder jpql = new StringBuilder("SELECT COUNT(m) FROM Movimento m WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        appendFilters(jpql, params, tipo, buId, categoriaId, metodoPagamentoId,
                stato, fornitoreId, eventoId, from, to, search);

        TypedQuery<Long> q = em.createQuery(jpql.toString(), Long.class);
        params.forEach(q::setParameter);
        return q.getSingleResult();
    }

    private void appendFilters(StringBuilder jpql, Map<String, Object> params,
            String tipo, Short buId, Long categoriaId, Integer metodoPagamentoId,
            String stato, UUID fornitoreId, UUID eventoId,
            LocalDate from, LocalDate to, String search) {

        if (tipo != null) {
            jpql.append(" AND m.tipo = :tipo");
            params.put("tipo", tipo);
        }
        if (buId != null) {
            jpql.append(" AND m.businessUnitId = :buId");
            params.put("buId", buId);
        }
        if (categoriaId != null) {
            jpql.append(" AND m.categoriaId = :categoriaId");
            params.put("categoriaId", categoriaId);
        }
        if (metodoPagamentoId != null) {
            jpql.append(" AND m.metodoPagamentoId = :metodoPagamentoId");
            params.put("metodoPagamentoId", metodoPagamentoId);
        }
        if (stato != null) {
            jpql.append(" AND m.stato = :stato");
            params.put("stato", stato);
        }
        if (fornitoreId != null) {
            jpql.append(" AND m.fornitoreId = :fornitoreId");
            params.put("fornitoreId", fornitoreId);
        }
        if (eventoId != null) {
            jpql.append(" AND m.eventoId = :eventoId");
            params.put("eventoId", eventoId);
        }
        if (from != null) {
            jpql.append(" AND m.dataMovimento >= :from");
            params.put("from", from);
        }
        if (to != null) {
            jpql.append(" AND m.dataMovimento <= :to");
            params.put("to", to);
        }
        if (search != null && !search.isBlank()) {
            jpql.append(" AND LOWER(m.descrizione) LIKE :search");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
    }

    private String buildSort(String sort) {
        if (sort == null) return " ORDER BY m.dataMovimento DESC";
        return switch (sort) {
            case "importo"    -> " ORDER BY m.importo DESC";
            case "categoria"  -> " ORDER BY m.categoriaId ASC NULLS LAST";
            default           -> " ORDER BY m.dataMovimento DESC";
        };
    }

    public List<Movimento> findByEventoId(UUID eventoId) {
        return list("eventoId = ?1 AND stato != 'ANNULLATO'", eventoId);
    }

    public boolean existsByRifEsterno(String fonte, String rifEsterno, LocalDate dataMovimento) {
        return count("fonte = ?1 AND riferimentoEsterno = ?2 AND dataMovimento = ?3",
                fonte, rifEsterno, dataMovimento) > 0;
    }

    public BigDecimal sumImportoByEventoId(UUID eventoId) {
        Object result = em.createQuery(
                        "SELECT SUM(m.importo) FROM Movimento m WHERE m.eventoId = :eid AND m.stato != 'ANNULLATO'")
                .setParameter("eid", eventoId)
                .getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }

    /**
     * Precarica in batch tutti i riferimentoEsterno esistenti per la fonte indicata.
     * Usato dal bulk import per deduplicazione O(1) con HashSet in memoria.
     */
    public Set<String> findRifimentiEsterniByFonte(String fonte) {
        @SuppressWarnings("unchecked")
        List<String> refs = em.createQuery(
                        "SELECT m.riferimentoEsterno FROM Movimento m WHERE m.fonte = :fonte AND m.riferimentoEsterno IS NOT NULL")
                .setParameter("fonte", fonte)
                .getResultList();
        return new HashSet<>(refs);
    }

    /** Movimenti non riconciliati da estratto conto bancario (per riconciliazione manuale). */
    public List<Movimento> findNonRiconciliatiDaBanca() {
        return list("stato = 'REGISTRATO' AND fonte = 'IMPORT_BANCA'");
    }
}

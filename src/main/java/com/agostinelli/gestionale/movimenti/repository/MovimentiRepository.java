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

    /** Movimenti attivi non ancora attribuiti a un conto/cassa (conto_bancario_id IS NULL). */
    public List<Movimento> findSenzaBanca() {
        return list("contoBancarioId IS NULL AND stato != 'ANNULLATO' ORDER BY dataMovimento DESC");
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

    /**
     * Aggregazione per stato e tipo, stessi filtri opzionali del findWithFilters.
     * Restituisce righe [stato, tipo, SUM(importo), COUNT(*)].
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> sommarioByStatoTipo(
            String tipo, Short buId, Long categoriaId, Integer metodoPagamentoId,
            String stato, UUID fornitoreId, UUID eventoId,
            LocalDate from, LocalDate to, String search) {

        StringBuilder jpql = new StringBuilder(
                "SELECT m.stato, m.tipo, SUM(m.importo), COUNT(m) FROM Movimento m WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        appendFilters(jpql, params, tipo, buId, categoriaId, metodoPagamentoId,
                stato, fornitoreId, eventoId, from, to, search);
        jpql.append(" GROUP BY m.stato, m.tipo ORDER BY m.stato, m.tipo");

        jakarta.persistence.Query q = em.createQuery(jpql.toString());
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    // ── Feature 1: movimenti DA_LIQUIDARE non ancora liquidi ────────────────────

    /**
     * Movimenti in stato DA_LIQUIDARE con data_finanziaria IS NULL (non ancora liquidi)
     * e data_liquidita nel passato (scadenza superata). Sono i movimenti "in ritardo":
     *  - tipo=USCITA → "sei in ritardo di tot giorni sul pagamento";
     *  - tipo=ENTRATA → "qualcuno è in ritardo di tot giorni nel pagarmi".
     *
     * Le rate dei piani di spesa ricorrente sono escluse per costruzione: lo scheduler
     * le converte in movimenti REGISTRATI alla scadenza (dataFinanziaria sempre valorizzata).
     *
     * @param tipo   filtro opzionale sulla direzione (ENTRATA/USCITA)
     * @param oggi   data di riferimento (passata in ingresso per poterla testare)
     * @param page   pagina 0-based
     * @param size   dimensione pagina
     * @param sort   ordinamento ("dataLiquidita"|"importo"|"dataMovimento", default dataLiquidita ASC)
     */
    public List<Movimento> findDaLiquidareInRitardo(String tipo, LocalDate oggi, int page, int size, String sort) {
        StringBuilder jpql = new StringBuilder("FROM Movimento m WHERE m.stato = 'DA_LIQUIDARE' " +
                "AND m.dataFinanziaria IS NULL AND m.dataLiquidita IS NOT NULL AND m.dataLiquidita < :oggi");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("oggi", oggi);
        if (tipo != null && !tipo.isBlank()) {
            jpql.append(" AND m.tipo = :tipo");
            params.put("tipo", tipo);
        }
        jpql.append(buildRitardoSort(sort));

        TypedQuery<Movimento> q = em.createQuery(jpql.toString(), Movimento.class)
                .setFirstResult(page * size)
                .setMaxResults(size);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    public long countDaLiquidareInRitardo(String tipo, LocalDate oggi) {
        StringBuilder jpql = new StringBuilder("SELECT COUNT(m) FROM Movimento m WHERE m.stato = 'DA_LIQUIDARE' " +
                "AND m.dataFinanziaria IS NULL AND m.dataLiquidita IS NOT NULL AND m.dataLiquidita < :oggi");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("oggi", oggi);
        if (tipo != null && !tipo.isBlank()) {
            jpql.append(" AND m.tipo = :tipo");
            params.put("tipo", tipo);
        }
        TypedQuery<Long> q = em.createQuery(jpql.toString(), Long.class);
        params.forEach(q::setParameter);
        return q.getSingleResult();
    }

    private String buildRitardoSort(String sort) {
        if (sort == null) return " ORDER BY m.dataLiquidita ASC";
        return switch (sort) {
            case "importo"      -> " ORDER BY m.importo DESC";
            case "dataMovimento"-> " ORDER BY m.dataMovimento DESC";
            default             -> " ORDER BY m.dataLiquidita ASC"; // default: scadenza più vecchia prima
        };
    }
}

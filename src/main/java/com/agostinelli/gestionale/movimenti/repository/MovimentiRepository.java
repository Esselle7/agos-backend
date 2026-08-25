package com.agostinelli.gestionale.movimenti.repository;

import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.MovimentiFilterQuery;
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
     * I criteri assenti vengono ignorati per non vincolare la query.
     */
    public List<Movimento> findWithFilters(MovimentiFilterQuery f, int page, int size, String sort) {
        StringBuilder jpql = new StringBuilder("FROM Movimento m WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        appendFilters(jpql, params, f);
        jpql.append(buildSort(sort));

        TypedQuery<Movimento> q = em.createQuery(jpql.toString(), Movimento.class)
                .setFirstResult(page * size)
                .setMaxResults(size);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    public long countWithFilters(MovimentiFilterQuery f) {
        StringBuilder jpql = new StringBuilder("SELECT COUNT(m) FROM Movimento m WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        appendFilters(jpql, params, f);

        TypedQuery<Long> q = em.createQuery(jpql.toString(), Long.class);
        params.forEach(q::setParameter);
        return q.getSingleResult();
    }

    /**
     * Unica fonte di verità dei predicati: la usano list, count e sommario, così il riepilogo
     * non può mai descrivere un insieme diverso da quello elencato (invariante di spec).
     *
     * Semantica: OR dentro la stessa dimensione (IN), AND fra dimensioni diverse.
     */
    private void appendFilters(StringBuilder jpql, Map<String, Object> params, MovimentiFilterQuery f) {
        in(jpql, params, "m.tipo",              "tipo",       f.tipo());
        in(jpql, params, "m.stato",             "stato",      f.stato());
        in(jpql, params, "m.fonte",             "fonte",      f.fonte());
        in(jpql, params, "m.contoCoge",         "cogeId",     f.cogeId());
        in(jpql, params, "m.businessUnitId",    "buId",       f.buId());
        in(jpql, params, "m.categoriaId",       "categoriaId", f.categoriaId());
        in(jpql, params, "m.metodoPagamentoId", "metodoPagamentoId", f.metodoPagamentoId());
        in(jpql, params, "m.fornitoreId",       "fornitoreId", f.fornitoreId());
        in(jpql, params, "m.eventoId",          "eventoId",   f.eventoId());

        appendContoFilter(jpql, params, f.contoId());

        // Range importo sul valore assoluto: l'utente ragiona per grandezza ("sopra 1.000 €"),
        // non per segno — la direzione la sceglie con il filtro tipo.
        if (f.importoMin() != null) {
            jpql.append(" AND ABS(m.importo) >= :importoMin");
            params.put("importoMin", f.importoMin());
        }
        if (f.importoMax() != null) {
            jpql.append(" AND ABS(m.importo) <= :importoMax");
            params.put("importoMax", f.importoMax());
        }

        // Il campo data arriva dall'enum, mai dalla stringa di richiesta.
        String dateField = f.dateField().field();
        if (f.from() != null) {
            jpql.append(" AND m.").append(dateField).append(" >= :from");
            params.put("from", f.from());
        }
        if (f.to() != null) {
            jpql.append(" AND m.").append(dateField).append(" <= :to");
            params.put("to", f.to());
        }

        if (f.search() != null && !f.search().isBlank()) {
            jpql.append(" AND LOWER(m.descrizione) LIKE :search");
            params.put("search", "%" + f.search().toLowerCase() + "%");
        }
    }

    /**
     * Il conto ha una semantica in più rispetto alle altre dimensioni: la sentinella 0 significa
     * «senza banca» (conto_bancario_id IS NULL), che in SQL non è esprimibile con una IN e va
     * messa in OR. Selezionare «Cassa + senza banca» deve tornare l'unione dei due insiemi.
     */
    private void appendContoFilter(StringBuilder jpql, Map<String, Object> params, List<Short> contoIds) {
        if (contoIds == null || contoIds.isEmpty()) return;

        List<Short> reali = contoIds.stream()
                .filter(id -> id != null && id != MovimentiFilterQuery.CONTO_SENZA_BANCA)
                .toList();
        boolean senzaBanca = contoIds.contains(MovimentiFilterQuery.CONTO_SENZA_BANCA);

        if (!reali.isEmpty() && senzaBanca) {
            jpql.append(" AND (m.contoBancarioId IN :contoId OR m.contoBancarioId IS NULL)");
            params.put("contoId", reali);
        } else if (!reali.isEmpty()) {
            jpql.append(" AND m.contoBancarioId IN :contoId");
            params.put("contoId", reali);
        } else if (senzaBanca) {
            jpql.append(" AND m.contoBancarioId IS NULL");
        }
    }

    /** Predicato IN su una dimensione; lista assente o vuota = nessun vincolo. */
    private void in(StringBuilder jpql, Map<String, Object> params,
                    String field, String param, List<?> values) {
        if (values == null || values.isEmpty()) return;
        List<?> clean = values.stream().filter(Objects::nonNull).toList();
        if (clean.isEmpty()) return;
        jpql.append(" AND ").append(field).append(" IN :").append(param);
        params.put(param, clean);
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

    /**
     * Come {@link #findByEventoId} ma per un'intera pagina di eventi, in UNA query.
     * Serve a {@code EventiService#findWithFilters}: chiamare findByEventoId per ogni evento
     * era un N+1 (misurato 15/08/2026: ~274 transazioni per 37 eventi).
     * Stesso identico predicato del metodo singolo, così le due strade non divergono.
     */
    public List<Movimento> findByEventoIds(Collection<UUID> eventoIds) {
        if (eventoIds.isEmpty()) return List.of();
        return list("eventoId IN ?1 AND stato != 'ANNULLATO'", eventoIds);
    }

    /**
     * Un pagamento-evento identico già a libro: stesso evento, tipo, importo, data finanziaria
     * e conto. Quella combinazione è lo <b>stesso bonifico registrato due volte</b>, non due
     * incassi veri — due incassi reali differiscono almeno per data o importo.
     *
     * <p>Serve alla guardia di {@code EventiService.registraPagamento}: la lettura sta nella
     * stessa transazione che scrive, quindi non può sfasarsi come farebbe un controllo fatto
     * a inizio import (nel caso reale che l'ha motivata passarono 2 giorni fra le due
     * registrazioni — vedi docs/specs/misure/incassi-evento-verifica-2026-08-14.md).
     *
     * <p>Con {@code contoBancarioId} null non blocca nulla: NULL = NULL è «unknown» in SQL.
     * È voluto — i pagamenti-evento hanno sempre un conto (il triage rifiuta senza,
     * {@code CONTO_BANCARIO_MANCANTE}) e restare fail-open su un caso che la guardia non
     * riguarda è meglio che inventare un confronto fra sconosciuti.
     *
     * <p><b>Sesto campo, la controparte (A4).</b> Due bonifici di persone diverse con stesso
     * importo, stesso giorno e stesso evento non sono un doppione: il 07/07/2026 due caparre da
     * 20,00 € (INDUNI RENATA e DE AGOSTINI M RCO) sullo stesso evento del 25/09. Il confronto è
     * <b>fail-closed</b>: solo una differenza <i>conosciuta</i> salva la riga, quindi una
     * controparte NULL — da un lato o da entrambi — non distingue e il gemello scatta lo stesso.
     * È la scrittura esplicita di ciò che SQL farebbe al contrario: con {@code = ?6} un NULL
     * riaprirebbe il buco di «Greg» (registrazione manuale senza controparte + conferma dal
     * wizard con controparte = due volte lo stesso bonifico a libro).
     */
    public Optional<Movimento> findPagamentoEventoGemello(
            UUID eventoId, String tipoEventoMovimento, BigDecimal importo,
            LocalDate dataFinanziaria, Short contoBancarioId, String controparte) {
        return find("eventoId = ?1 AND tipoEventoMovimento = ?2 AND importo = ?3 "
                  + "AND dataFinanziaria = ?4 AND contoBancarioId = ?5 AND stato != 'ANNULLATO' "
                  + "AND (controparte IS NULL OR ?6 IS NULL OR controparte = ?6)",
                eventoId, tipoEventoMovimento, importo, dataFinanziaria, contoBancarioId, controparte)
                .firstResultOptional();
    }

    /**
     * Movimenti attivi non ancora attribuiti a un conto/cassa (conto_bancario_id IS NULL).
     * Le righe COMPETENZA hanno il conto NULL per invariante I2 (non sono denaro entrato):
     * proporle qui le faceva attribuire a mano, gonfiando i saldi di 4.020 € su CA (25/08/2026).
     */
    public List<Movimento> findSenzaBanca() {
        return list("contoBancarioId IS NULL AND stato != 'ANNULLATO' " +
                    "AND (tipoEventoMovimento IS NULL OR tipoEventoMovimento <> 'COMPETENZA') " +
                    "ORDER BY dataMovimento DESC");
    }

    /** Partite di apertura: crediti/debiti pregressi (fonte APERTURA) ancora da liquidare. */
    public List<Movimento> findPartiteApertura(String tipo) {
        return list("fonte = 'APERTURA' AND stato = 'DA_LIQUIDARE' AND tipo = ?1 ORDER BY dataLiquidita", tipo);
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
    public List<Object[]> sommarioByStatoTipo(MovimentiFilterQuery f) {
        StringBuilder jpql = new StringBuilder(
                "SELECT m.stato, m.tipo, SUM(m.importo), COUNT(m) FROM Movimento m WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        appendFilters(jpql, params, f);
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

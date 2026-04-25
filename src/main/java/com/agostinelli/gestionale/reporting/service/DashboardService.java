package com.agostinelli.gestionale.reporting.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.cache.DashboardCacheKeyGenerator;
import com.agostinelli.gestionale.reporting.dto.*;
import com.agostinelli.gestionale.shared.dto.MovimentoDTO;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped
public class DashboardService {

    // Colori BU hardcoded — dato di presentazione stabile, non in DB
    private static final Map<Short, String> BU_COLORI = Map.of(
            (short) 1, "#4CAF50",
            (short) 2, "#2196F3",
            (short) 3, "#FF9800",
            (short) 4, "#8BC34A",
            (short) 5, "#9E9E9E"
    );

    private static final Map<Short, String> BU_NOMI = Map.of(
            (short) 1, "Ristorazione",
            (short) 2, "Cerimonie",
            (short) 3, "Spaccio",
            (short) 4, "Verde",
            (short) 5, "Overhead"
    );

    @Inject
    EntityManager em;

    // ── GET /api/dashboard/kpi ────────────────────────────────────────────────

    @CacheResult(cacheName = "dashboard-kpi", keyGenerator = DashboardCacheKeyGenerator.class)
    @Transactional
    public DashboardKpiDTO getKpi(LocalDate from, LocalDate to, String userId) {
        validateRange(from, to);
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        // Query 1: aggregati KPI da mv_kpi_mensili
        Object[] kpi = queryKpiAggregati(fromYM, toYM);
        BigDecimal totalEntrate = toBD(kpi[0]);
        BigDecimal totalUscite  = toBD(kpi[1]);
        BigDecimal margine      = toBD(kpi[2]);
        long       nMovimenti   = toLong(kpi[3]);

        BigDecimal marginePct = totalEntrate.compareTo(BigDecimal.ZERO) > 0
                ? margine.divide(totalEntrate, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;

        // Query 2: saldi conti da mv_saldi_conti (max 5 righe)
        @SuppressWarnings("unchecked")
        List<Object[]> saldiRows = em.createNativeQuery(
                "SELECT conto_id, saldo_calcolato FROM mv_saldi_conti ORDER BY conto_id")
                .getResultList();

        Map<Integer, BigDecimal> saldiMap = new HashMap<>();
        for (Object[] row : saldiRows) {
            saldiMap.put(toInt(row[0]), toBD(row[1]));
        }

        // variazioneMese per conti bancari (1=BPM, 2=CA) dal cash flow del mese corrente
        Map<Integer, BigDecimal> variazioneMap = queryVariazioneMese(to.getYear(), to.getMonthValue());

        BigDecimal saldoBpm    = saldiMap.getOrDefault(1, BigDecimal.ZERO);
        BigDecimal saldoCa     = saldiMap.getOrDefault(2, BigDecimal.ZERO);
        BigDecimal saldoCassa  = saldiMap.getOrDefault(3, BigDecimal.ZERO);
        BigDecimal saldoTotale = saldiMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        DashboardKpiDTO.SaldiDTO saldi = new DashboardKpiDTO.SaldiDTO(
                new DashboardKpiDTO.ContoSaldoDTO(saldoBpm,    variazioneMap.get(1)),
                new DashboardKpiDTO.ContoSaldoDTO(saldoCa,     variazioneMap.get(2)),
                new DashboardKpiDTO.ContoSaldoDTO(saldoCassa,  null),
                new DashboardKpiDTO.ContoSaldoDTO(saldoTotale, null)
        );

        DashboardKpiDTO.PeriodoDTO periodo = new DashboardKpiDTO.PeriodoDTO(
                from, to, totalEntrate, totalUscite, margine, marginePct, nMovimenti);

        // calcolaMesePrecedente: stessa query spostata indietro di 1 mese (stessa sessione)
        DashboardKpiDTO.DeltaMesePrecedenteDTO delta =
                calcolaMesePrecedente(from, to, totalEntrate, totalUscite, margine);

        return new DashboardKpiDTO(saldi, periodo, delta, Instant.now());
    }

    // ── GET /api/dashboard/andamento-mensile ─────────────────────────────────

    @CacheResult(cacheName = "dashboard-andamento", keyGenerator = DashboardCacheKeyGenerator.class)
    @Transactional
    public List<AndamentoMensileDTO> getAndamentoMensile(int anni, String userId) {
        if (anni > 5) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PARAM_OUT_OF_RANGE", "anni max 5");
        }
        int startYear = LocalDate.now().getYear() - anni + 1;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT anno, mese, " +
                "COALESCE(SUM(totale_entrate),0), " +
                "COALESCE(SUM(totale_uscite),0), " +
                "COALESCE(SUM(margine),0) " +
                "FROM mv_kpi_mensili " +
                "WHERE anno >= :startYear " +
                "GROUP BY anno, mese ORDER BY anno ASC, mese ASC")
                .setParameter("startYear", startYear)
                .getResultList();

        return rows.stream()
                .map(r -> new AndamentoMensileDTO(toInt(r[0]), toInt(r[1]), toBD(r[2]), toBD(r[3]), toBD(r[4])))
                .toList();
    }

    // ── GET /api/dashboard/fatturato-per-bu ──────────────────────────────────

    @CacheResult(cacheName = "dashboard-bufatturato", keyGenerator = DashboardCacheKeyGenerator.class)
    @Transactional
    public List<FatturatoPerBuDTO> getFatturatoPerBu(LocalDate from, LocalDate to, String userId) {
        validateRange(from, to);
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT business_unit_id, business_unit_nome, " +
                "COALESCE(SUM(totale_entrate),0), " +
                "COALESCE(SUM(totale_uscite),0), " +
                "COALESCE(SUM(margine),0) " +
                "FROM mv_kpi_mensili " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM " +
                "GROUP BY business_unit_id, business_unit_nome")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM)
                .getResultList();

        Map<Short, FatturatoPerBuDTO> risultati = new LinkedHashMap<>();
        for (Object[] r : rows) {
            short buId       = toShort(r[0]);
            BigDecimal entr  = toBD(r[2]);
            BigDecimal usc   = toBD(r[3]);
            BigDecimal marg  = toBD(r[4]);
            BigDecimal mPct  = entr.compareTo(BigDecimal.ZERO) > 0
                    ? marg.divide(entr, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;
            risultati.put(buId, new FatturatoPerBuDTO(
                    buId, (String) r[1], BU_COLORI.getOrDefault(buId, "#607D8B"),
                    entr, usc, marg, mPct));
        }

        // Includere tutte le 5 BU anche con zero per coerenza frontend (grafico pie)
        for (short id = 1; id <= 5; id++) {
            risultati.putIfAbsent(id, new FatturatoPerBuDTO(
                    id, BU_NOMI.getOrDefault(id, "BU" + id),
                    BU_COLORI.getOrDefault(id, "#607D8B"),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        return risultati.values().stream()
                .sorted(Comparator.comparing(FatturatoPerBuDTO::totEntrate).reversed())
                .toList();
    }

    // ── GET /api/dashboard/ultime-transazioni ────────────────────────────────
    // WHY query nativa e NON MV: dati real-time; un movimento appena inserito
    // deve apparire subito. Le MV si aggiornano ogni 30 min.

    @Transactional
    public List<MovimentoDTO> getUltimeTransazioni(int limit) {
        int safeLimit = Math.min(limit, 50);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.id, m.data_movimento, m.tipo, m.importo_lordo, " +
                "m.descrizione, m.stato, m.fonte, " +
                "c.nome AS categoria_nome, bu.nome AS bu_nome, cb.nome AS conto_nome " +
                "FROM movimenti m " +
                "LEFT JOIN categorie c ON c.id = m.categoria_id " +
                "JOIN business_units bu ON bu.id = m.business_unit_id " +
                "JOIN conti_bancari cb ON cb.id = m.conto_bancario_id " +
                "WHERE m.stato != 'ANNULLATO' " +
                "ORDER BY m.data_movimento DESC, m.created_at DESC NULLS LAST " +
                "LIMIT :limit")
                .setParameter("limit", safeLimit)
                .getResultList();

        return rows.stream().map(this::mapRowToMovimentoDTO).toList();
    }

    // ── GET /api/dashboard/scadenze-imminenti ────────────────────────────────
    // WHY query diretta su eventi: importoIncassato è aggiornato da trigger
    // real-time; la MV mv_redditivita_eventi sarebbe stale in modo inaccettabile.

    @Transactional
    public List<ScadenzaDTO> getScadenzeImminenti(int giorni) {
        int safeGiorni = Math.min(giorni, 90);
        LocalDate oggi  = LocalDate.now();
        LocalDate limite = oggi.plusDays(safeGiorni);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT e.id, e.nome, e.data_evento, " +
                "(e.importo_totale_preventivato - e.importo_incassato) AS importo_residuo " +
                "FROM eventi e " +
                "WHERE e.stato = 'CONFERMATO' " +
                "AND (e.importo_totale_preventivato - e.importo_incassato) > 0 " +
                "AND e.data_evento BETWEEN :oggi AND :limite " +
                "ORDER BY e.data_evento ASC")
                .setParameter("oggi", oggi)
                .setParameter("limite", limite)
                .getResultList();

        return rows.stream().map(r -> {
            LocalDate dataEvento = toLocalDate(r[2]);
            long giorniAllaScadenza = ChronoUnit.DAYS.between(oggi, dataEvento);
            String urgenza;
            if      (giorniAllaScadenza < 7)  urgenza = "ALTA";
            else if (giorniAllaScadenza < 15) urgenza = "MEDIA";
            else                              urgenza = "BASSA";

            return new ScadenzaDTO("SALDO_EVENTO", toUUID(r[0]), (String) r[1],
                    toBD(r[3]), dataEvento, urgenza);
        }).toList();
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private Object[] queryKpiAggregati(int fromYM, int toYM) {
        return (Object[]) em.createNativeQuery(
                "SELECT COALESCE(SUM(totale_entrate),0), COALESCE(SUM(totale_uscite),0), " +
                "COALESCE(SUM(margine),0), COALESCE(SUM(n_movimenti),0) " +
                "FROM mv_kpi_mensili " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM)
                .getSingleResult();
    }

    private Map<Integer, BigDecimal> queryVariazioneMese(int anno, int mese) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT conto_bancario_id, " +
                "entrate_operative + entrate_finanziarie " +
                "- uscite_operative - uscite_investimento - uscite_finanziarie " +
                "FROM mv_cash_flow_statement " +
                "WHERE anno = :anno AND mese = :mese AND conto_bancario_id IN (1, 2)")
                .setParameter("anno", anno)
                .setParameter("mese", mese)
                .getResultList();

        Map<Integer, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(toInt(row[0]), toBD(row[1]));
        }
        return map;
    }

    private DashboardKpiDTO.DeltaMesePrecedenteDTO calcolaMesePrecedente(
            LocalDate from, LocalDate to,
            BigDecimal entrateCorrente, BigDecimal usciteCorrente, BigDecimal margineCorrente) {

        LocalDate prevFrom = from.minusMonths(1);
        LocalDate prevTo   = to.minusMonths(1);
        int prevFromYM = prevFrom.getYear() * 100 + prevFrom.getMonthValue();
        int prevToYM   = prevTo.getYear()   * 100 + prevTo.getMonthValue();

        Object[] prev = queryKpiAggregati(prevFromYM, prevToYM);
        BigDecimal prevEntrate = toBD(prev[0]);
        BigDecimal prevUscite  = toBD(prev[1]);
        BigDecimal prevMargine = toBD(prev[2]);

        BigDecimal entrateDelta = entrateCorrente.subtract(prevEntrate);
        BigDecimal usciteDelta  = usciteCorrente.subtract(prevUscite);
        BigDecimal margineDelta = margineCorrente.subtract(prevMargine);
        BigDecimal deltaPercent = prevEntrate.compareTo(BigDecimal.ZERO) > 0
                ? entrateDelta.divide(prevEntrate, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;

        return new DashboardKpiDTO.DeltaMesePrecedenteDTO(entrateDelta, usciteDelta, margineDelta, deltaPercent);
    }

    private MovimentoDTO mapRowToMovimentoDTO(Object[] r) {
        // r[0]=id, r[1]=data_movimento, r[2]=tipo, r[3]=importo_lordo,
        // r[4]=descrizione, r[5]=stato, r[6]=fonte,
        // r[7]=categoria_nome, r[8]=bu_nome, r[9]=conto_nome
        BigDecimal importoLordo = toBD(r[3]);
        return new MovimentoDTO(
                toUUID(r[0]),        // id
                (String) r[2],       // tipo
                importoLordo,        // importo
                toLocalDate(r[1]),   // dataMovimento
                null,                // dataCompetenza
                null,                // dataLiquidita
                null,                // canale
                null,                // contoId
                (String) r[9],       // contoNome
                null,                // businessUnitId
                (String) r[8],       // businessUnitNome
                null,                // categoriaId
                (String) r[7],       // categoriaNome
                null,                // sottocategoriaId
                null,                // sottocategoriaNome
                null,                // fornitoreId
                null,                // fornitoreNome
                null,                // eventoId
                null,                // eventoNome
                null,                // tipoEventoMovimento
                (String) r[4],       // descrizione
                null,                // note
                importoLordo,        // importoLordo
                null,                // importoCommissione
                null,                // aliquotaIva
                null,                // importoIva
                (String) r[5],       // stato
                (String) r[6],       // fonte
                null,                // allegatoPath
                null,                // createdAt
                null                 // createdBy
        );
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_RANGE",
                    "from non può essere successivo a to");
        }
    }

    // ── type-cast helpers ─────────────────────────────────────────────────────

    private BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private short toShort(Object o) {
        if (o instanceof Number n) return n.shortValue();
        return Short.parseShort(o.toString());
    }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString());
    }

    private UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}

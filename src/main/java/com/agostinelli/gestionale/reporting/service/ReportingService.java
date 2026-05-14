package com.agostinelli.gestionale.reporting.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.dto.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ReportingService {

    @Inject
    EntityManager em;

    // ── GET /api/reporting/pl ─────────────────────────────────────────────────
    // WHY MV e non query diretta: mv_conto_economico_mensile usa data_competenza
    // (economica) e aggrega per conto_coge — aggregare su movimenti grezzo
    // richiederebbe full-scan con JOIN a piano_dei_conti_coge e business_units.

    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @Transactional
    public PlDTO computePl(Short buId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        String buFilter = buId != null ? " AND business_unit_id = :buId" : "";
        @SuppressWarnings("unchecked")
        var query = em.createNativeQuery(
                "SELECT codice_coge, descrizione_coge, tipo_coge, is_capex, " +
                "COALESCE(SUM(ricavi),0), COALESCE(SUM(costi_operativi),0), " +
                "COALESCE(SUM(investimenti_capex),0), COALESCE(SUM(ebitda_proxy),0) " +
                "FROM mv_conto_economico_mensile " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM" + buFilter +
                " GROUP BY codice_coge, descrizione_coge, tipo_coge, is_capex")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM);
        if (buId != null) query.setParameter("buId", buId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return buildPlDto(buId, from, to, rows);
    }

    // Versione senza filtro buId usata dal job e da /pl/tutte-bu
    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @Transactional
    public PlDTO computePlAllBu(LocalDate from, LocalDate to) {
        return computePl(null, from, to);
    }

    // ── GET /api/reporting/pl/tutte-bu ───────────────────────────────────────

    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @Transactional
    public PlComparativoDTO computePlComparativo(LocalDate from, LocalDate to) {
        validateRange(from, to);
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT business_unit_id, business_unit_nome, " +
                "COALESCE(SUM(ricavi),0), COALESCE(SUM(costi_operativi),0), " +
                "COALESCE(SUM(investimenti_capex),0), COALESCE(SUM(ebitda_proxy),0) " +
                "FROM mv_conto_economico_mensile " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM " +
                "GROUP BY business_unit_id, business_unit_nome")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM)
                .getResultList();

        List<PlComparativoDTO.PlBuDTO> buList = new ArrayList<>();
        BigDecimal totRicavi = BigDecimal.ZERO;
        BigDecimal totCosti  = BigDecimal.ZERO;
        BigDecimal totEbitda = BigDecimal.ZERO;

        for (Object[] r : rows) {
            short buId    = toShort(r[0]);
            BigDecimal ric  = toBD(r[2]);
            BigDecimal cos  = toBD(r[3]).add(toBD(r[4])); // operativi + capex
            BigDecimal ebit = toBD(r[5]);
            BigDecimal mPct = ric.compareTo(BigDecimal.ZERO) > 0
                    ? ebit.divide(ric, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            buList.add(new PlComparativoDTO.PlBuDTO(
                    new BuRefDTO(buId, (String) r[1]), ric, cos, ebit, mPct));

            totRicavi = totRicavi.add(ric);
            totCosti  = totCosti.add(cos);
            totEbitda = totEbitda.add(ebit);
        }

        BigDecimal totMargPct = totRicavi.compareTo(BigDecimal.ZERO) > 0
                ? totEbitda.divide(totRicavi, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        return new PlComparativoDTO(from, to, buList,
                new PlComparativoDTO.ConsolidatoDTO(totRicavi, totCosti, totEbitda, totMargPct));
    }

    // ── GET /api/reporting/cashflow/storico ───────────────────────────────────

    @Transactional
    public List<CashFlowPeriodoDTO> getCashFlowStorico(LocalDate from, LocalDate to, String granularity) {
        validateRange(from, to);

        if ("WEEK".equals(granularity)) {
            long mesi = ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1));
            if (mesi > 6) {
                throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_TOO_LARGE",
                        "Granolarità settimanale supportata solo per range ≤ 6 mesi");
            }
            return getCashFlowSettimanale(from, to);
        }

        // MONTH: usa mv_cash_flow_statement
        return getCashFlowMensile(from, to);
    }

    // ── GET /api/reporting/cashflow/forecast ─────────────────────────────────

    @Transactional
    public List<ForecastPointDTO> getCashFlowForecast(int giorni) {
        int safeGiorni = Math.min(giorni, 365);
        LocalDate oggi = LocalDate.now();

        // Parte 1 – STORICO: ultimi 30 giorni con granularità giornaliera.
        // WHY data_finanziaria: il cash flow storico deve usare la data di liquidazione
        // effettiva (non quella economica) per riflettere i soldi realmente mossi.
        LocalDate storFrom = oggi.minusDays(30);
        @SuppressWarnings("unchecked")
        List<Object[]> storRows = em.createNativeQuery(
                "SELECT data_finanziaria, " +
                "COALESCE(SUM(CASE WHEN tipo='ENTRATA' THEN importo_lordo ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN tipo='USCITA'  THEN importo_lordo ELSE 0 END),0) " +
                "FROM movimenti " +
                "WHERE stato != 'ANNULLATO' " +
                "AND data_finanziaria IS NOT NULL " +
                "AND data_finanziaria BETWEEN :from AND :oggi " +
                "GROUP BY data_finanziaria ORDER BY data_finanziaria ASC")
                .setParameter("from", storFrom)
                .setParameter("oggi", oggi)
                .getResultList();

        // Liquidità iniziale = somma saldi_calcolati da mv_saldi_conti
        BigDecimal saldoIniziale = ((BigDecimal) em.createNativeQuery(
                "SELECT COALESCE(SUM(saldo_calcolato),0) FROM mv_saldi_conti")
                .getSingleResult());

        List<ForecastPointDTO> risultato = new ArrayList<>();
        BigDecimal saldoCumulato = saldoIniziale;

        // Calcola saldo cumulato storico
        Map<LocalDate, Object[]> storMap = new LinkedHashMap<>();
        for (Object[] r : storRows) {
            storMap.put(toLocalDate(r[0]), r);
        }
        for (LocalDate d = storFrom; !d.isAfter(oggi); d = d.plusDays(1)) {
            Object[] r = storMap.get(d);
            BigDecimal entr = r != null ? toBD(r[1]) : BigDecimal.ZERO;
            BigDecimal usc  = r != null ? toBD(r[2]) : BigDecimal.ZERO;
            saldoCumulato = saldoCumulato.add(entr).subtract(usc);
            risultato.add(new ForecastPointDTO(d, "STORICO", entr, usc, saldoCumulato, null));
        }

        // Parte 2 – PREVISTO: prossimi {safeGiorni} giorni
        // WHY: usiamo importoResiduo evento come stima incasso; nella realtà il cliente
        // può pagare in anticipo, in ritardo, o non pagare. Il forecast è indicativo.
        LocalDate prevFine = oggi.plusDays(safeGiorni);
        @SuppressWarnings("unchecked")
        List<Object[]> eventiRows = em.createNativeQuery(
                "SELECT data_evento, SUM(importo_totale_preventivato - importo_incassato) " +
                "FROM eventi " +
                "WHERE stato = 'CONFERMATO' " +
                "AND importo_incassato < importo_totale_preventivato " +
                "AND data_evento BETWEEN :oggi AND :fine " +
                "GROUP BY data_evento")
                .setParameter("oggi", oggi.plusDays(1))
                .setParameter("fine", prevFine)
                .getResultList();

        Map<LocalDate, BigDecimal> eventiMap = new HashMap<>();
        for (Object[] r : eventiRows) {
            eventiMap.put(toLocalDate(r[0]), toBD(r[1]));
        }

        for (LocalDate d = oggi.plusDays(1); !d.isAfter(prevFine); d = d.plusDays(1)) {
            BigDecimal entr = eventiMap.getOrDefault(d, BigDecimal.ZERO);
            BigDecimal usc  = BigDecimal.ZERO; // TODO: leggere da scadenze_fisse quando disponibile
            saldoCumulato = saldoCumulato.add(entr).subtract(usc);
            String note = eventiMap.containsKey(d) ? "Saldo atteso eventi: " + entr : null;
            risultato.add(new ForecastPointDTO(d, "PREVISTO", entr, usc, saldoCumulato, note));
        }

        return risultato;
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private PlDTO buildPlDto(Short buId, LocalDate from, LocalDate to, List<Object[]> rows) {
        List<VoceDTO> vociRicavi  = new ArrayList<>();
        List<VoceDTO> vociCosti   = new ArrayList<>();
        BigDecimal totRicavi = BigDecimal.ZERO;
        BigDecimal totCosti  = BigDecimal.ZERO;
        BigDecimal totCapex  = BigDecimal.ZERO;
        BigDecimal totEbitda = BigDecimal.ZERO;

        // Prima passata: calcola i totali per calcolare le percentuali
        for (Object[] r : rows) {
            totRicavi = totRicavi.add(toBD(r[4]));
            totCosti  = totCosti.add(toBD(r[5]));
            totCapex  = totCapex.add(toBD(r[6]));
            totEbitda = totEbitda.add(toBD(r[7]));
        }

        BigDecimal baseRicavi = totRicavi.compareTo(BigDecimal.ZERO) > 0 ? totRicavi : BigDecimal.ONE;
        BigDecimal baseCosti  = totCosti.add(totCapex).compareTo(BigDecimal.ZERO) > 0
                ? totCosti.add(totCapex) : BigDecimal.ONE;

        for (Object[] r : rows) {
            String codice = (String) r[0];
            String desc   = (String) r[1];
            BigDecimal ric = toBD(r[4]);
            BigDecimal cos = toBD(r[5]);
            BigDecimal cap = toBD(r[6]);

            if (ric.compareTo(BigDecimal.ZERO) > 0) {
                vociRicavi.add(new VoceDTO(codice, desc, ric,
                        ric.divide(baseRicavi, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))));
            }
            if (cos.add(cap).compareTo(BigDecimal.ZERO) > 0) {
                vociCosti.add(new VoceDTO(codice, desc, cos.add(cap),
                        cos.add(cap).divide(baseCosti, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))));
            }
        }

        BigDecimal marginePct = totRicavi.compareTo(BigDecimal.ZERO) > 0
                ? totEbitda.divide(totRicavi, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;

        BuRefDTO buRef = buId != null ? new BuRefDTO(buId, null) : null;

        return new PlDTO(
                buRef, from, to,
                new PlDTO.RicaviDTO(totRicavi, vociRicavi),
                new PlDTO.CostiDTO(totCosti.add(totCapex), totCapex, vociCosti),
                totEbitda, marginePct
        );
    }

    private List<CashFlowPeriodoDTO> getCashFlowMensile(LocalDate from, LocalDate to) {
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT anno, mese, " +
                "COALESCE(SUM(entrate_operative + entrate_finanziarie),0), " +
                "COALESCE(SUM(uscite_operative + uscite_investimento + uscite_finanziarie),0) " +
                "FROM mv_cash_flow_statement " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM " +
                "GROUP BY anno, mese ORDER BY anno ASC, mese ASC")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM)
                .getResultList();

        List<CashFlowPeriodoDTO> result = new ArrayList<>();
        BigDecimal cumulato = BigDecimal.ZERO;
        for (Object[] r : rows) {
            int anno = toInt(r[0]);
            int mese = toInt(r[1]);
            BigDecimal entr = toBD(r[2]);
            BigDecimal usc  = toBD(r[3]);
            BigDecimal saldo = entr.subtract(usc);
            cumulato = cumulato.add(saldo);
            LocalDate inizio = LocalDate.of(anno, mese, 1);
            LocalDate fine   = inizio.withDayOfMonth(inizio.lengthOfMonth());
            result.add(new CashFlowPeriodoDTO(inizio, fine, entr, usc, saldo, cumulato));
        }
        return result;
    }

    private List<CashFlowPeriodoDTO> getCashFlowSettimanale(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DATE_TRUNC('week', data_movimento) AS settimana, " +
                "COALESCE(SUM(CASE WHEN tipo='ENTRATA' THEN importo_lordo ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN tipo='USCITA'  THEN importo_lordo ELSE 0 END),0) " +
                "FROM movimenti " +
                "WHERE stato != 'ANNULLATO' AND data_movimento BETWEEN :from AND :to " +
                "GROUP BY DATE_TRUNC('week', data_movimento) " +
                "ORDER BY settimana ASC")
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<CashFlowPeriodoDTO> result = new ArrayList<>();
        BigDecimal cumulato = BigDecimal.ZERO;
        for (Object[] r : rows) {
            LocalDate inizio = toLocalDate(r[0]);
            BigDecimal entr = toBD(r[1]);
            BigDecimal usc  = toBD(r[2]);
            BigDecimal saldo = entr.subtract(usc);
            cumulato = cumulato.add(saldo);
            result.add(new CashFlowPeriodoDTO(inizio, inizio.plusDays(6), entr, usc, saldo, cumulato));
        }
        return result;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) return;
        if (from.isAfter(to)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_RANGE",
                    "from non può essere successivo a to");
        }
        if (ChronoUnit.YEARS.between(from, to) >= 5) {
            throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_TOO_LARGE",
                    "Range massimo consentito: 5 anni");
        }
    }

    // ── type-cast helpers ─────────────────────────────────────────────────────

    private BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
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
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }
}

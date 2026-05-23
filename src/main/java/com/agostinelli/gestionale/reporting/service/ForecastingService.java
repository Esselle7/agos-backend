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
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.*;

/**
 * Calcola la previsione economico-finanziaria (Forecasting).
 *
 * DESIGN: tutte le query sono read-only su tabelle e MV esistenti.
 * Nessun side-effect sul DB.
 *
 * STRATEGIA DE-DUPLICAZIONE EVENTI vs DA_LIQUIDARE:
 * I movimenti DA_LIQUIDARE con evento_id vengono esclusi dalle query
 * "movimentiDaLiquidare" e "movimentiEconomici". Il residuo atteso
 * dall'evento (preventivato - incassato) cattura già quella quota, con
 * data_evento come data di riferimento per entrambe le viste.
 */
@ApplicationScoped
public class ForecastingService {

    @Inject
    EntityManager em;

    @Transactional
    @Timeout(value = 15, unit = ChronoUnit.SECONDS)
    public ForecastingRispostaDTO computeForecasting(String horizon) {
        LocalDate oggi = LocalDate.now();
        LocalDate fine = computeFine(horizon, oggi);

        // Se FINE_ANNO e siamo già il 31/12, il periodo forward è vuoto
        if (!fine.isAfter(oggi)) {
            ForecastingAsIsDTO asIs = buildAsIs(oggi);
            ForecastingEconomicoDTO economico = new ForecastingEconomicoDTO(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of());
            ForecastingFinanziarioDTO finanziario = new ForecastingFinanziarioDTO(
                    asIs.saldoLiquidita(), BigDecimal.ZERO, BigDecimal.ZERO,
                    asIs.saldoLiquidita(), List.of());
            return new ForecastingRispostaDTO(asIs, economico, finanziario);
        }

        LocalDate start = oggi.plusDays(1);

        ForecastingAsIsDTO asIs = buildAsIs(oggi);
        ForecastingEconomicoDTO economico = buildEconomico(start, fine);
        ForecastingFinanziarioDTO finanziario = buildFinanziario(start, fine, asIs.saldoLiquidita(), horizon);

        return new ForecastingRispostaDTO(asIs, economico, finanziario);
    }

    // ── Horizon ───────────────────────────────────────────────────────────────

    private LocalDate computeFine(String horizon, LocalDate oggi) {
        if (horizon == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "MISSING_HORIZON",
                    "Il parametro horizon è obbligatorio");
        }
        return switch (horizon.toUpperCase()) {
            case "30"        -> oggi.plusDays(30);
            case "60"        -> oggi.plusDays(60);
            case "90"        -> oggi.plusDays(90);
            case "180"       -> oggi.plusDays(180);
            case "FINE_ANNO" -> LocalDate.of(oggi.getYear(), 12, 31);
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_HORIZON",
                    "Horizon deve essere uno di: 30, 60, 90, 180, FINE_ANNO");
        };
    }

    // ── AS IS ─────────────────────────────────────────────────────────────────

    private ForecastingAsIsDTO buildAsIs(LocalDate oggi) {
        // Saldo liquidità attuale (conti bancari + cassa)
        BigDecimal saldo = toBD(em.createNativeQuery(
                "SELECT COALESCE(SUM(saldo_calcolato),0) FROM mv_saldi_conti")
                .getSingleResult());

        // Ricavi/Costi/EBITDA YTD dall'inizio dell'anno a questo mese
        int fromYM = oggi.getYear() * 100 + 1;
        int toYM   = oggi.getYear() * 100 + oggi.getMonthValue();
        Object[] ytd = (Object[]) em.createNativeQuery(
                "SELECT COALESCE(SUM(ricavi),0), COALESCE(SUM(costi_operativi),0), COALESCE(SUM(ebitda_proxy),0) " +
                "FROM mv_conto_economico_mensile " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM)
                .getSingleResult();

        // Crediti e debiti aperti: movimenti non ancora liquidati (data_finanziaria IS NULL)
        Object[] daLiq = (Object[]) em.createNativeQuery(
                "SELECT " +
                "COALESCE(SUM(CASE WHEN tipo='ENTRATA' THEN importo_lordo ELSE 0 END),0) AS crediti, " +
                "COALESCE(SUM(CASE WHEN tipo='USCITA'  THEN importo_lordo ELSE 0 END),0) AS debiti " +
                "FROM movimenti " +
                "WHERE stato != 'ANNULLATO' AND data_finanziaria IS NULL")
                .getSingleResult();

        return new ForecastingAsIsDTO(
                saldo,
                toBD(ytd[0]), toBD(ytd[1]), toBD(ytd[2]),
                toBD(daLiq[0]), toBD(daLiq[1]));
    }

    // ── ECONOMICO ─────────────────────────────────────────────────────────────

    private ForecastingEconomicoDTO buildEconomico(LocalDate start, LocalDate end) {
        List<ForecastingDettaglioDTO> dettaglio = new ArrayList<>();

        // 1a. Movimenti con data economica futura (impatto P&L previsto)
        dettaglio.addAll(buildMovimentiEconomici(start, end));

        // 1b. Movimenti DA_LIQUIDARE con competenza passata ma cassa futura.
        //     Sono già nel P&L storico (YTD), ma vanno mostrati nella tabella
        //     dettaglio perché l'utente vede solo questa lista. Le aggregazioni
        //     P&L sotto filtrano vista="FINANZIARIA" per non doppiocontarli.
        dettaglio.addAll(buildMovimentiDaLiquidare(start, end));

        // 2. Residuo atteso da eventi CONFERMATI
        dettaglio.addAll(buildEventiForecasting(start, end));

        // 3. Rate ricorrenti PENDING (con split capitale/interessi per FINANZIAMENTO)
        dettaglio.addAll(buildRatePending(start, end));

        // 4. Stipendi
        dettaglio.addAll(buildStipendi(start, end));

        dettaglio.sort(Comparator.comparing(ForecastingDettaglioDTO::data));

        // Aggregati P&L: escludono FINANZIARIA-only (impatto solo cassa, P&L già storico)
        BigDecimal ricavi = dettaglio.stream()
                .filter(d -> !"FINANZIARIA".equals(d.vista()))
                .map(ForecastingDettaglioDTO::importoEntrata)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // costiPrevisti: solo costi operativi (esclude quota capitale, interessi FINANZIAMENTO e movimenti finanziari-only)
        BigDecimal costiOperativi = dettaglio.stream()
                .filter(d -> !"FINANZIARIA".equals(d.vista()))
                .filter(d -> !"RATA_RICORRENTE_CAPITALE".equals(d.categoria())
                          && !"RATA_RICORRENTE_INTERESSI".equals(d.categoria()))
                .map(ForecastingDettaglioDTO::importoUscita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal oneriFinanziari = dettaglio.stream()
                .filter(d -> "RATA_RICORRENTE_INTERESSI".equals(d.categoria()))
                .map(ForecastingDettaglioDTO::importoUscita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal ebitda = ricavi.subtract(costiOperativi);
        BigDecimal ammortamenti = computeAmmortamentiPrevisione(start, end);
        BigDecimal ebit = ebitda.subtract(ammortamenti);

        return new ForecastingEconomicoDTO(ricavi, costiOperativi, ebitda, oneriFinanziari, ebit, dettaglio);
    }

    private BigDecimal computeAmmortamentiPrevisione(LocalDate from, LocalDate to) {
        long mesi = java.time.temporal.ChronoUnit.MONTHS.between(
                from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1;
        Object result = em.createNativeQuery(
                "SELECT COALESCE(SUM(costo_storico * aliquota_ammortamento / 100.0 / 12.0), 0) " +
                "FROM cespiti WHERE is_active = true")
                .getSingleResult();
        BigDecimal ammMensile = result instanceof BigDecimal bd ? bd : new BigDecimal(result.toString());
        return ammMensile.multiply(BigDecimal.valueOf(mesi)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ── FINANZIARIO ───────────────────────────────────────────────────────────

    private ForecastingFinanziarioDTO buildFinanziario(LocalDate start, LocalDate end,
                                                        BigDecimal saldoPartenza, String horizon) {
        List<ForecastingDettaglioDTO> items = new ArrayList<>();

        // 1. Movimenti DA_LIQUIDARE con data liquidità nel periodo
        //    (escludendo quelli con evento_id, catturati dall'evento residuo)
        items.addAll(buildMovimentiDaLiquidare(start, end));

        // 2. Residuo eventi (stesso del calcolo economico: data_evento è data attesa incasso)
        items.addAll(buildEventiForecasting(start, end));

        // 3. Rate ricorrenti PENDING
        items.addAll(buildRatePending(start, end));

        // 4. Stipendi
        items.addAll(buildStipendi(start, end));

        BigDecimal incassi = items.stream()
                .map(ForecastingDettaglioDTO::importoEntrata)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal uscite = items.stream()
                .map(ForecastingDettaglioDTO::importoUscita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean granularitaSettimanale = isSettimanale(horizon);
        List<ForecastingTimelineDTO> timeline = buildTimeline(items, start, end, saldoPartenza, granularitaSettimanale);

        return new ForecastingFinanziarioDTO(
                saldoPartenza, incassi, uscite,
                saldoPartenza.add(incassi).subtract(uscite),
                timeline);
    }

    // ── Fonti previsione ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<ForecastingDettaglioDTO> buildMovimentiEconomici(LocalDate start, LocalDate end) {
        // Le ENTRATE con evento_id sono escluse perché il residuo entrate dell'evento le rappresenta.
        // Le USCITE con evento_id (costi diretti: F&B, personale extra, ...) NON sono catturate dal residuo
        // e devono essere mostrate per non sottostimare i costi previsti.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT data_competenza, tipo, " +
                "COALESCE(importo_imponibile, importo_lordo) AS importo, " +
                "COALESCE(descrizione, 'Movimento') AS desc " +
                "FROM movimenti " +
                "WHERE stato != 'ANNULLATO' " +
                "AND data_competenza BETWEEN :start AND :end " +
                "AND NOT (evento_id IS NOT NULL AND tipo = 'ENTRATA') " +
                "ORDER BY data_competenza ASC")
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();

        List<ForecastingDettaglioDTO> result = new ArrayList<>();
        for (Object[] r : rows) {
            LocalDate data = toLocalDate(r[0]);
            String tipo = (String) r[1];
            BigDecimal importo = toBD(r[2]);
            String desc = (String) r[3];
            result.add(new ForecastingDettaglioDTO(
                    data, "MOVIMENTO", desc,
                    "ENTRATA".equals(tipo) ? importo : BigDecimal.ZERO,
                    "USCITA".equals(tipo)  ? importo : BigDecimal.ZERO,
                    "ECONOMICA"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ForecastingDettaglioDTO> buildMovimentiDaLiquidare(LocalDate start, LocalDate end) {
        // Filtri:
        //  - data_liquidita <= end (includiamo anche le scadenze in arretrato data < start:
        //    una rata/fattura non onorata è cassa che deve ancora uscire/entrare).
        //  - ENTRATE con evento_id ESCLUSE: il residuo entrate dell'evento viene già
        //    catturato da buildEventiForecasting (preventivato - incassato), evita doppio conteggio.
        //  - USCITE con evento_id INCLUSE: i costi diretti dell'evento (fornitori F&B, personale extra…)
        //    NON sono rappresentati dal residuo evento, vanno mostrati esplicitamente.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT data_liquidita, tipo, importo_lordo, " +
                "COALESCE(descrizione, 'Pagamento atteso') AS desc " +
                "FROM movimenti " +
                "WHERE stato != 'ANNULLATO' " +
                "AND data_finanziaria IS NULL " +
                "AND data_liquidita <= :end " +
                "AND NOT (evento_id IS NOT NULL AND tipo = 'ENTRATA') " +
                "ORDER BY data_liquidita ASC")
                .setParameter("end", end)
                .getResultList();

        List<ForecastingDettaglioDTO> result = new ArrayList<>();
        for (Object[] r : rows) {
            LocalDate data = toLocalDate(r[0]);
            String tipo = (String) r[1];
            BigDecimal importo = toBD(r[2]);
            String desc = (String) r[3];
            result.add(new ForecastingDettaglioDTO(
                    data, "MOVIMENTO", desc,
                    "ENTRATA".equals(tipo) ? importo : BigDecimal.ZERO,
                    "USCITA".equals(tipo)  ? importo : BigDecimal.ZERO,
                    "FINANZIARIA"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ForecastingDettaglioDTO> buildEventiForecasting(LocalDate start, LocalDate end) {
        // Residuo = preventivato - incassato (solo eventi confermati non ancora saldati)
        // importo_totale_preventivato è nullable → COALESCE a 0 come fallback conservativo
        List<Object[]> rows = em.createNativeQuery(
                "SELECT e.data_evento, COALESCE(e.nome, 'Evento'), " +
                "GREATEST(0, COALESCE(e.importo_totale_preventivato, 0) - COALESCE(e.importo_incassato, 0)) " +
                "FROM eventi e " +
                "WHERE e.stato = 'CONFERMATO' " +
                "AND COALESCE(e.importo_totale_preventivato, 0) > COALESCE(e.importo_incassato, 0) " +
                "AND e.data_evento BETWEEN :start AND :end " +
                "ORDER BY e.data_evento ASC")
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();

        List<ForecastingDettaglioDTO> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal residuo = toBD(r[2]);
            if (residuo.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new ForecastingDettaglioDTO(
                        toLocalDate(r[0]),
                        "EVENTO",
                        (String) r[1],
                        residuo,
                        BigDecimal.ZERO,
                        "ENTRAMBE"));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ForecastingDettaglioDTO> buildRatePending(LocalDate start, LocalDate end) {
        // data_scadenza <= end (includiamo anche le rate scadute non onorate: sono cassa
        // ancora attesa, lo scheduler tenta di pagarle al prossimo tick utile).
        // Filtriamo p.stato = 'ATTIVO' per evitare rate dimenticate su piani ANNULLATO/COMPLETATO
        // (caso teorico: cancelPlan e completePlan dovrebbero già spostarle, ma è una safeguard).
        List<Object[]> rows = em.createNativeQuery(
                "SELECT i.data_scadenza, COALESCE(p.descrizione, 'Spesa ricorrente'), i.importo, " +
                "p.tipo_piano, i.quota_capitale, i.quota_interessi " +
                "FROM recurring_expense_installment i " +
                "JOIN recurring_expense_plan p ON p.id = i.piano_id " +
                "WHERE i.stato = 'PENDING' " +
                "AND p.stato = 'ATTIVO' " +
                "AND i.data_scadenza <= :end " +
                "ORDER BY i.data_scadenza ASC")
                .setParameter("end", end)
                .getResultList();

        List<ForecastingDettaglioDTO> result = new ArrayList<>();
        for (Object[] r : rows) {
            LocalDate data    = toLocalDate(r[0]);
            String desc       = (String) r[1];
            String tipoPiano  = r[3] != null ? (String) r[3] : "FLAT";

            if ("FINANZIAMENTO".equals(tipoPiano) && r[4] != null && r[5] != null) {
                BigDecimal quotaCapitale  = toBD(r[4]);
                BigDecimal quotaInteressi = toBD(r[5]);
                result.add(new ForecastingDettaglioDTO(
                        data, "RATA_RICORRENTE_CAPITALE", desc + " (capitale)",
                        BigDecimal.ZERO, quotaCapitale, "ENTRAMBE"));
                result.add(new ForecastingDettaglioDTO(
                        data, "RATA_RICORRENTE_INTERESSI", desc + " (interessi)",
                        BigDecimal.ZERO, quotaInteressi, "ENTRAMBE"));
            } else {
                result.add(new ForecastingDettaglioDTO(
                        data, "RATA_RICORRENTE", desc,
                        BigDecimal.ZERO, toBD(r[2]), "ENTRAMBE"));
            }
        }
        return result;
    }

    private List<ForecastingDettaglioDTO> buildStipendi(LocalDate start, LocalDate end) {
        // Query aggregata: totale stipendi e numero dipendenti attivi
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT COALESCE(SUM(costo_aziendale_mensile), 0), COUNT(*) " +
                "FROM personale " +
                "WHERE is_active = true " +
                "AND costo_aziendale_mensile IS NOT NULL " +
                "AND costo_aziendale_mensile > 0")
                .getSingleResult();

        BigDecimal totale = toBD(row[0]);
        int numDip = ((Number) row[1]).intValue();

        if (numDip == 0 || totale.compareTo(BigDecimal.ZERO) == 0) {
            return List.of();
        }

        // Genera un pagamento il giorno 28 (o ultimo giorno del mese se < 28) per ogni mese nel periodo
        List<ForecastingDettaglioDTO> result = new ArrayList<>();
        YearMonth ymStart = YearMonth.from(start);
        YearMonth ymEnd   = YearMonth.from(end);

        for (YearMonth ym = ymStart; !ym.isAfter(ymEnd); ym = ym.plusMonths(1)) {
            int payDay = Math.min(28, ym.lengthOfMonth());
            LocalDate payDate = ym.atDay(payDay);

            if (!payDate.isBefore(start) && !payDate.isAfter(end)) {
                result.add(new ForecastingDettaglioDTO(
                        payDate,
                        "STIPENDIO",
                        "Stipendi " + numDip + " dipendenti",
                        BigDecimal.ZERO,
                        totale,
                        "ENTRAMBE"));
            }
        }
        return result;
    }

    // ── Timeline aggregata ────────────────────────────────────────────────────

    private List<ForecastingTimelineDTO> buildTimeline(
            List<ForecastingDettaglioDTO> items,
            LocalDate start, LocalDate end,
            BigDecimal saldoPartenza,
            boolean settimanale) {

        // Aggrega items per bucket
        Map<String, BigDecimal[]> bucketMap = new LinkedHashMap<>();
        Map<String, LocalDate[]>  bucketBounds = new LinkedHashMap<>();

        // Popola tutti i bucket nell'intervallo per garantire continuità
        if (settimanale) {
            LocalDate cur = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            while (!cur.isAfter(end)) {
                String key = bucketKeyWeek(cur);
                bucketMap.put(key, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                LocalDate wEnd = cur.plusDays(6);
                bucketBounds.put(key, new LocalDate[]{
                    cur.isBefore(start) ? start : cur,
                    wEnd.isAfter(end) ? end : wEnd});
                cur = cur.plusWeeks(1);
            }
        } else {
            YearMonth ymStart = YearMonth.from(start);
            YearMonth ymEnd   = YearMonth.from(end);
            for (YearMonth ym = ymStart; !ym.isAfter(ymEnd); ym = ym.plusMonths(1)) {
                String key = bucketKeyMonth(ym.atDay(1));
                bucketMap.put(key, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                LocalDate mStart = ym.atDay(1);
                LocalDate mEnd   = ym.atEndOfMonth();
                bucketBounds.put(key, new LocalDate[]{
                    mStart.isBefore(start) ? start : mStart,
                    mEnd.isAfter(end) ? end : mEnd});
            }
        }

        // Primo bucket: usato come fallback per gli item in arretrato (data < start) così che
        // i loro flussi entrino comunque nella proiezione (sono cassa ancora attesa) e non
        // diventino "invisibili" nella timeline pur essendo conteggiati nei totali.
        String firstBucketKey = bucketMap.keySet().iterator().next();

        // Accumula flussi
        for (ForecastingDettaglioDTO item : items) {
            // Gli item ECONOMICA-only non entrano nella timeline finanziaria
            if ("ECONOMICA".equals(item.vista())) continue;
            if (item.data() == null) continue;

            String key;
            if (item.data().isBefore(start)) {
                key = firstBucketKey;
            } else if (item.data().isAfter(end)) {
                continue;
            } else {
                key = settimanale ? bucketKeyWeek(item.data()) : bucketKeyMonth(item.data());
            }
            BigDecimal[] vals = bucketMap.get(key);
            if (vals == null) continue;
            vals[0] = vals[0].add(item.importoEntrata());
            vals[1] = vals[1].add(item.importoUscita());
        }

        // Costruisce la lista ordinata con saldo progressivo
        List<ForecastingTimelineDTO> result = new ArrayList<>();
        BigDecimal saldo = saldoPartenza;
        for (Map.Entry<String, BigDecimal[]> e : bucketMap.entrySet()) {
            String key = e.getKey();
            BigDecimal entr = e.getValue()[0];
            BigDecimal usc  = e.getValue()[1];
            BigDecimal ebitda = entr.subtract(usc);
            saldo = saldo.add(entr).subtract(usc);
            LocalDate[] bounds = bucketBounds.get(key);
            result.add(new ForecastingTimelineDTO(key, bounds[0], bounds[1], entr, usc, ebitda, saldo));
        }
        return result;
    }

    private boolean isSettimanale(String horizon) {
        return switch (horizon.toUpperCase()) {
            case "30", "60", "90" -> true;
            default -> false;  // 180, FINE_ANNO → mensile
        };
    }

    private String bucketKeyWeek(LocalDate date) {
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = monday.get(IsoFields.WEEK_BASED_YEAR);
        return year + "-W" + String.format("%02d", week);
    }

    private String bucketKeyMonth(LocalDate date) {
        return date.getYear() + "-" + String.format("%02d", date.getMonthValue());
    }

    // ── type-cast helpers (duplicati da ReportingService per autonomia) ────────

    private BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }
}

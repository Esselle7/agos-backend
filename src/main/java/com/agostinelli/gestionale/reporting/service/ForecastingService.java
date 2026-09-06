package com.agostinelli.gestionale.reporting.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.Perimetro;
import com.agostinelli.gestionale.reporting.dto.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** Segmento (conto, dow) escluso dalla stima se osservato in meno di N giorni distinti (anti-rumore). */
    @ConfigProperty(name = "forecast.baseline.min-giorni", defaultValue = "4")
    int minGiorni;

    /** Oltre questo orizzonte la stima non viene proiettata (solo certo): una media settimanale su
     *  mesi lontani ignora la stagione. */
    @ConfigProperty(name = "forecast.baseline.orizzonte-giorni", defaultValue = "90")
    int orizzonteStimaGiorni;

    @ConfigProperty(name = "forecast.baseline.finestra-settimane", defaultValue = "8")
    int finestraSettimane;

    /** Oltre questi giorni senza cassa a libro il previsionale dichiara che sta prevedendo su dati
     *  fermi (P6). Default 14: due settimane senza estratto conto sono già un buco che cambia la
     *  lettura di ogni numero della pagina. */
    @ConfigProperty(name = "forecast.freschezza.giorni-max", defaultValue = "14")
    int freschezzaGiorniMax;

    /** Mesi INTERI di dati richiesti perché la stima costi si accenda (P7, gate fail-closed).
     *  Default 3, deciso dall'utente il 06/09/2026: con go-live al 01/07 significa non prima di
     *  ottobre 2026. Una media su due mesi, di cui uno è luglio dove il 29,6% dei costi sta ancora
     *  sul transitorio «da classificare», è il rumore di due campioni, non una stima. */
    @ConfigProperty(name = "forecast.costi.min-mesi", defaultValue = "3")
    int minMesiCosti;

    /** Ampiezza della finestra su cui si media, in mesi interi. */
    @ConfigProperty(name = "forecast.costi.finestra-mesi", defaultValue = "3")
    int finestraMesiCosti;

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
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), BigDecimal.ZERO, null);
            ForecastingFinanziarioDTO finanziario = new ForecastingFinanziarioDTO(
                    asIs.saldoLiquidita(), BigDecimal.ZERO, BigDecimal.ZERO,
                    asIs.saldoLiquidita(), List.of());
            return new ForecastingRispostaDTO(asIs, economico, finanziario);
        }

        LocalDate start = oggi.plusDays(1);

        // Layer STIMATO: ricavi cash proiettati dalla baseline. Calcolato una volta e passato a
        // entrambe le viste (dettaglio economico + timeline finanziaria).
        StimaRicaviCash stima = projectStimaRicaviCash(start, fine, oggi);

        // asIs PRIMA della stima costi: il gate di P7 legge datiIncompleti (freschezza del dato).
        ForecastingAsIsDTO asIs = buildAsIs(oggi);
        StimaCosti stimaCosti = projectStimaCosti(start, fine, oggi, asIs.datiIncompleti());

        ForecastingEconomicoDTO economico =
                buildEconomico(start, fine, stima.righe(), stimaCosti);
        ForecastingFinanziarioDTO finanziario = buildFinanziario(
                start, fine, asIs.saldoLiquidita(), horizon, stima.giornaliera(), stimaCosti.perData());

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

        // P5 (docs/specs/previsionale-correzioni.md): lo YTD si ferma a OGGI, non a fine mese.
        // La MV ha grana mensile, quindi il mese corrente entra intero — comprese le caparre con
        // data_competenza futura di eventi non ancora celebrati (misura 06/09/2026: 3.420,00 € di
        // ricavo non maturato dentro un numero etichettato «YTD», B14 della baseline). Dopo P2 gli
        // stessi euro stanno anche nel previsionale: senza questa sottrazione sarebbero contati due
        // volte a video.
        //
        // Confine: YTD = [1 gen, oggi] · previsione = (oggi, fine]. Nessuna sovrapposizione, nessun buco.
        //
        // La coda si sottrae con LO STESSO JOIN e LA STESSA semantica CASE di V39 — altrimenti si
        // sottrarrebbe una cosa diversa da quella che si è sommata. Il JOIN su business_units c'è
        // perché la MV lo ha: una riga con BU non in anagrafica la MV non la conta, e nemmeno questa.
        Object[] coda = (Object[]) em.createNativeQuery(
                "SELECT " +
                " COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' AND pc.tipo='RICAVO' " +
                "      THEN COALESCE(m.importo_imponibile, m.importo_lordo) " +
                "      WHEN m.tipo='USCITA' AND pc.tipo='RICAVO' " +
                "      THEN -COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END),0), " +
                " COALESCE(SUM(CASE WHEN m.tipo='USCITA' AND pc.tipo='COSTO' AND NOT pc.is_capex " +
                "      THEN COALESCE(m.importo_imponibile, m.importo_lordo) " +
                "      WHEN m.tipo='ENTRATA' AND pc.tipo='COSTO' AND NOT pc.is_capex " +
                "       AND COALESCE(m.importo_imponibile, m.importo_lordo) < 0 " +
                "      THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END),0) " +
                "FROM movimenti m " +
                "JOIN business_units bu ON bu.id = m.business_unit_id " +
                "JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                "WHERE m.stato <> 'ANNULLATO' AND m.data_competenza > :oggi AND m.data_competenza <= :fineMese")
                .setParameter("oggi", oggi)
                .setParameter("fineMese", oggi.withDayOfMonth(oggi.lengthOfMonth()))
                .getSingleResult();

        BigDecimal ricaviYtd = toBD(ytd[0]).subtract(toBD(coda[0]));
        BigDecimal costiYtd  = toBD(ytd[1]).subtract(toBD(coda[1]));
        // L'ebitda_proxy della MV è ricavi − costi: la coda si toglie con la stessa differenza,
        // così l'invariante ebitdaYtd = ricaviYtd − costiYtd resta vera per costruzione.
        BigDecimal ebitdaYtd = toBD(ytd[2]).subtract(toBD(coda[0]).subtract(toBD(coda[1])));

        // Crediti e debiti aperti: movimenti non ancora liquidati (data_finanziaria IS NULL).
        // La terza colonna è il credito da eventi già celebrati (P4): stessa base, più
        // evento_id IS NOT NULL — è la stessa condizione di ReportingService.computeQualita, senza
        // filtro di periodo perché riguarda eventi passati e non dipende dall'orizzonte.
        // Calcolarlo QUI e non con una query a parte rende il sottoinsieme strutturale: non può
        // superare creditiAperti perché è la stessa SUM con un CASE più stretto.
        Object[] daLiq = (Object[]) em.createNativeQuery(
                "SELECT " +
                "COALESCE(SUM(CASE WHEN tipo='ENTRATA' THEN importo_lordo ELSE 0 END),0) AS crediti, " +
                "COALESCE(SUM(CASE WHEN tipo='USCITA'  THEN importo_lordo ELSE 0 END),0) AS debiti, " +
                "COALESCE(SUM(CASE WHEN tipo='ENTRATA' AND evento_id IS NOT NULL " +
                "         THEN importo_lordo ELSE 0 END),0) AS credito_eventi " +
                "FROM movimenti " +
                "WHERE stato != 'ANNULLATO' AND data_finanziaria IS NULL")
                .getSingleResult();

        // P6: su che dato stiamo prevedendo. Il previsionale affermava «saldo fra 90 giorni:
        // 20.964,18 €» con la stessa faccia sia che il dato fosse di ieri sia che fosse fermo da
        // sette settimane — ed è l'informazione che rende interpretabili tutte le altre.
        // Stesso pattern di ReportingService.computeQualita: un boolean + una nota che dice il numero.
        LocalDate ultimaDataCassa = toLocalDate(em.createNativeQuery(
                "SELECT MAX(data_finanziaria) FROM movimenti WHERE stato != 'ANNULLATO'")
                .getSingleResult());
        boolean datiIncompleti = ultimaDataCassa == null
                || ultimaDataCassa.isBefore(oggi.minusDays(freschezzaGiorniMax));

        return new ForecastingAsIsDTO(
                saldo,
                ricaviYtd, costiYtd, ebitdaYtd,
                toBD(daLiq[0]), toBD(daLiq[1]), toBD(daLiq[2]),
                ultimaDataCassa, datiIncompleti,
                datiIncompleti ? notaFreschezza(ultimaDataCassa, oggi) : null);
    }

    private static final String[] MESI = {"gennaio", "febbraio", "marzo", "aprile", "maggio",
            "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"};

    /** Dice la data e i mesi scoperti. Nessun giudizio, solo i numeri: chi legge decide. */
    private String notaFreschezza(LocalDate ultima, LocalDate oggi) {
        if (ultima == null) {
            return "Nessun movimento con data di incasso a libro: la previsione parte dal saldo "
                 + "iniziale, senza storico di cassa.";
        }
        long giorni = ChronoUnit.DAYS.between(ultima, oggi);
        StringBuilder sb = new StringBuilder("Ultimo movimento di cassa a libro: ")
                .append(ultima.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                .append(" (").append(giorni).append(giorni == 1 ? " giorno fa)." : " giorni fa).");

        List<String> scoperti = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(ultima).plusMonths(1);
             !ym.isAfter(YearMonth.from(oggi)); ym = ym.plusMonths(1)) {
            scoperti.add(MESI[ym.getMonthValue() - 1] + " " + ym.getYear());
        }
        if (!scoperti.isEmpty()) {
            sb.append(" Nessun incasso importato per: ").append(String.join(", ", scoperti)).append('.');
        }
        return sb.toString();
    }

    // ── ECONOMICO ─────────────────────────────────────────────────────────────

    private ForecastingEconomicoDTO buildEconomico(LocalDate start, LocalDate end,
                                                   List<ForecastingDettaglioDTO> righeStimate,
                                                   StimaCosti stimaCosti) {
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

        // 5. Quote ammortamento cespiti (competenza economica, non cassa): una riga per cespite
        //    nel dettaglio, così l'utente vede DA DOVE arriva il totale ammortamenti.
        List<ForecastingDettaglioDTO> ammRighe = buildAmmortamenti(start, end);
        dettaglio.addAll(ammRighe);

        // 6. Ricavi cash STIMATI (layer non-certo, aggregati per conto). Mostrati nel dettaglio con
        //    flag affidabilita=STIMATO, ma esclusi dai subtotali P&L "certi" sotto.
        dettaglio.addAll(righeStimate);

        // 7. Costi ricorrenti STIMATI (P7). Come i ricavi stimati: nel dettaglio con flag
        //    affidabilita=STIMATO, fuori dai subtotali certi.
        dettaglio.addAll(stimaCosti.righe());

        dettaglio.sort(Comparator.comparing(ForecastingDettaglioDTO::data));

        // Aggregati P&L "certi": escludono FINANZIARIA-only (cassa, P&L già storico) e le voci STIMATE
        // (il combinato certo+stimato lo compone il frontend dai flag/entrateStimate).
        BigDecimal ricavi = dettaglio.stream()
                .filter(d -> !"FINANZIARIA".equals(d.vista()))
                .filter(d -> !"STIMATO".equals(d.affidabilita()))
                .map(ForecastingDettaglioDTO::importoEntrata)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // costiPrevisti: solo costi operativi (esclude quota capitale, interessi FINANZIAMENTO,
        // movimenti finanziari-only e ammortamenti — questi ultimi stanno tra EBITDA ed EBIT)
        BigDecimal costiOperativi = dettaglio.stream()
                .filter(d -> !"FINANZIARIA".equals(d.vista()))
                // P7: costiPrevisti resta il CERTO, esattamente come ricaviPrevisti. Il combinato
                // lo compone la UI dai flag. Senza questo filtro le righe stimate entrerebbero nei
                // subtotali certi e l'EBITDA previsto mescolerebbe misura e congettura.
                .filter(d -> !"STIMATO".equals(d.affidabilita()))
                .filter(d -> !"RATA_RICORRENTE_CAPITALE".equals(d.categoria())
                          && !"RATA_RICORRENTE_INTERESSI".equals(d.categoria())
                          && !"AMMORTAMENTO".equals(d.categoria()))
                .map(ForecastingDettaglioDTO::importoUscita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal oneriFinanziari = dettaglio.stream()
                .filter(d -> "RATA_RICORRENTE_INTERESSI".equals(d.categoria()))
                .map(ForecastingDettaglioDTO::importoUscita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal ebitda = ricavi.subtract(costiOperativi);
        BigDecimal ammortamenti = ammRighe.stream()
                .map(ForecastingDettaglioDTO::importoUscita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ebit = ebitda.subtract(ammortamenti);
        // P3: gli oneri finanziari stanno SOTTO l'EBIT. Prima erano mostrati a video come riga di
        // sottrazione ma non venivano sottratti da nessun totale: aritmetica falsa sullo schermo.
        BigDecimal ebt = ebit.subtract(oneriFinanziari);

        return new ForecastingEconomicoDTO(ricavi, costiOperativi, ebitda, ammortamenti,
                oneriFinanziari, ebit, dettaglio, ebt, stimaCosti.nota());
    }

    /**
     * Quote di ammortamento di competenza nel periodo: UNA riga per cespite PER MESE, allineata
     * alla convenzione contabile del P&L (ReportingService.computeAmmortamenti, fonte di verità):
     * quota mensile fissa = costo × aliquota% / 1200, mese intero (nessun pro-rata sui giorni), la
     * quota matura all'ULTIMO GIORNO del mese. Una riga è emessa se quella data cade in [from, to]
     * e il mese rientra nella finestra di vita [mese di data_acquisto, mese di fine vita =
     * data_acquisto + 1200/aliquota mesi).
     *
     * Nota quadratura: la quota per riga è arrotondata a 2 decimali → su 6 mesi Σ = 1.000,02 mentre
     * il P&L (che arrotonda il TOTALE a fine calcolo) dà 1.000,00. Il delta di centesimi è atteso e
     * accettabile: entrambe le viste usano la stessa base mensile 166,67; il P&L resta la verità.
     * Vista ECONOMICA: entra nel dettaglio e nel P&L previsto, NON nella timeline di cassa.
     */
    @SuppressWarnings("unchecked")
    private List<ForecastingDettaglioDTO> buildAmmortamenti(LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT descrizione, costo_storico, aliquota_ammortamento, data_acquisto " +
                "FROM cespiti WHERE is_active = true AND aliquota_ammortamento > 0")
                .getResultList();

        List<ForecastingDettaglioDTO> result = new ArrayList<>();
        YearMonth ymFrom = YearMonth.from(from);
        YearMonth ymTo   = YearMonth.from(to);
        for (Object[] r : rows) {
            BigDecimal costo = toBD(r[1]);
            BigDecimal aliq  = toBD(r[2]);
            LocalDate acquisto = toLocalDate(r[3]);

            // Quota mensile del P&L, arrotondata a 2 dec (una riga = una quota di competenza).
            BigDecimal quota = costo.multiply(aliq)
                    .divide(BigDecimal.valueOf(1200), 2, java.math.RoundingMode.HALF_UP);
            if (quota.signum() <= 0) continue;

            int vitaMesi = BigDecimal.valueOf(1200)
                    .divide(aliq, 0, java.math.RoundingMode.HALF_UP).intValue();
            YearMonth inizio   = YearMonth.from(acquisto);
            YearMonth fineEscl = inizio.plusMonths(vitaMesi);  // primo mese NON ammortizzato

            for (YearMonth ym = ymFrom; !ym.isAfter(ymTo); ym = ym.plusMonths(1)) {
                if (ym.isBefore(inizio) || !ym.isBefore(fineEscl)) continue;   // fuori vita
                LocalDate ultimoGiorno = ym.atEndOfMonth();
                if (ultimoGiorno.isBefore(from) || ultimoGiorno.isAfter(to)) continue; // fine mese fuori orizzonte
                result.add(new ForecastingDettaglioDTO(
                        ultimoGiorno, "AMMORTAMENTO", "Ammortamento " + r[0] + " (quota mese)",
                        BigDecimal.ZERO, quota, "ECONOMICA", "CERTO"));
            }
        }
        return result;
    }

    // ── FINANZIARIO ───────────────────────────────────────────────────────────

    private ForecastingFinanziarioDTO buildFinanziario(LocalDate start, LocalDate end,
                                                        BigDecimal saldoPartenza, String horizon,
                                                        Map<LocalDate, BigDecimal> stimaGiornaliera,
                                                        Map<LocalDate, BigDecimal> stimaCostiPerData) {
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
        List<ForecastingTimelineDTO> timeline = buildTimeline(
                items, start, end, saldoPartenza, granularitaSettimanale,
                stimaGiornaliera, stimaCostiPerData);

        return new ForecastingFinanziarioDTO(
                saldoPartenza, incassi, uscite,
                saldoPartenza.add(incassi).subtract(uscite),
                timeline);
    }

    // ── Fonti previsione ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<ForecastingDettaglioDTO> buildMovimentiEconomici(LocalDate start, LocalDate end) {
        // P2 (docs/specs/previsionale-correzioni.md): le ENTRATE con evento_id NON sono più escluse.
        // Erano escluse «perché il residuo dell'evento le rappresenta», ma la motivazione era
        // invertita: il residuo vale preventivato − incassato, cioè il COMPLEMENTO di queste righe.
        // Escluderle e mostrare solo il residuo teneva fuori dal ricavo previsto l'acconto già
        // incassato — la cui data_competenza è la data dell'evento, quindi futura — mentre
        // mv_conto_economico_mensile lo conta in quel mese (raggruppa su data_competenza).
        // Misura 06/09/2026: 4.420,00 € su orizzonte 90 (B2/B3 della baseline).
        //
        // Nessun doppio conteggio in cassa: queste righe nascono vista=ECONOMICA, che
        // buildTimeline scarta; gli acconti hanno data_finanziaria valorizzata, quindi non sono
        // nemmeno raccolti da buildMovimentiDaLiquidare — dove la clausola gemella RESTA, ed è lì
        // che tiene le righe COMPETENZA fuori dalla proiezione di cassa.
        //
        // Le USCITE con evento_id (costi diretti: F&B, personale extra, ...) non sono catturate dal
        // residuo e devono essere mostrate per non sottostimare i costi previsti.
        //
        // JOIN su piano_dei_conti_coge (collaudo 06/09/2026). La spec lo teneva «fuori scopo,
        // latente, 0 occorrenze» lasciando la trappola R2.5 — e la trappola è diventata ROSSA:
        // `Filtro date range` e `Filtro buId 2`, due ENTRATA con competenza futura su conti
        // ATTIVITA, venivano contate come ricavo previsto. Il controesempio è un test rosso, che
        // il CLAUDE.md ammette come misura.
        //
        // La semantica replicata è quella di mv_conto_economico_mensile (V39), non una nuova: il
        // previsionale deve promettere il conto economico che quel mese produrrà davvero
        // (obiettivo 1 della spec). Quindi ENTRATA conta come ricavo solo su conto RICAVO non
        // capex, USCITA come costo solo su conto COSTO non capex, e le righe di tipo discorde
        // valgono zero — esattamente come nel CASE della MV.
        //
        // Impatto misurato su agosdb (copia di produzione) al 06/09/2026, finestra (oggi, +180]:
        // 0 entrate e 0 uscite escluse, 0,00 € mossi, 0 righe senza conto_coge_id. Chiude il buco
        // senza spostare un centesimo della baseline.
        //
        // Limite DICHIARATO, non svista: un'uscita su conto ONERE_FINANZIARIO o IMPOSTA esce dal
        // dettaglio invece di scendere sotto l'EBIT, e un movimento senza conto_coge_id esce del
        // tutto (come esce dalla MV, che ha lo stesso inner join). Occorrenze nella finestra al
        // 06/09/2026: 0 e 0. Gli oneri finanziari previsti oggi arrivano dai piani ricorrenti, non
        // dai movimenti; portarceli anche da qui è un passo diverso, da aprire con la sua misura.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.data_competenza, m.tipo, " +
                "COALESCE(m.importo_imponibile, m.importo_lordo) AS importo, " +
                "COALESCE(m.descrizione, 'Movimento') AS desc " +
                "FROM movimenti m " +
                "JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                "WHERE m.stato != 'ANNULLATO' " +
                "AND m.data_competenza BETWEEN :start AND :end " +
                "AND NOT pc.is_capex " +
                "AND ((m.tipo = 'ENTRATA' AND pc.tipo = 'RICAVO') " +
                "  OR (m.tipo = 'USCITA'  AND pc.tipo = 'COSTO')) " +
                "ORDER BY m.data_competenza ASC")
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
                    "ECONOMICA", "CERTO"));
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
                    "FINANZIARIA", "CERTO"));
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
                        "ENTRAMBE", "CERTO"));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<ForecastingDettaglioDTO> buildRatePending(LocalDate start, LocalDate end) {
        // data_scadenza <= end: includiamo anche le rate GIÀ SCADUTE e ancora PENDING, perché sono
        // cassa ancora attesa — la banca non le ha (ancora) addebitate. Dal 2026-08-05 non esiste più
        // un job che le "paga" da solo alla scadenza: restano qui finché non le conferma l'estratto
        // conto (COLLEGA dall'import) o l'utente a mano. Una rata scaduta che non sparisce da questo
        // elenco è quindi un segnale da leggere, non un residuo da ignorare.
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
                        BigDecimal.ZERO, quotaCapitale, "ENTRAMBE", "CERTO"));
                result.add(new ForecastingDettaglioDTO(
                        data, "RATA_RICORRENTE_INTERESSI", desc + " (interessi)",
                        BigDecimal.ZERO, quotaInteressi, "ENTRAMBE", "CERTO"));
            } else {
                result.add(new ForecastingDettaglioDTO(
                        data, "RATA_RICORRENTE", desc,
                        BigDecimal.ZERO, toBD(r[2]), "ENTRAMBE", "CERTO"));
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
                        "ENTRAMBE", "CERTO"));
            }
        }
        return result;
    }

    // ── Stima ricavi cash (layer STIMATO) ─────────────────────────────────────

    /** Risultato della proiezione: righe aggregate per conto (dettaglio) + totale per giorno (timeline). */
    private record StimaRicaviCash(List<ForecastingDettaglioDTO> righe,
                                   Map<LocalDate, BigDecimal> giornaliera) {}

    /**
     * Proietta i ricavi cash stimati leggendo {@code forecast_baseline} (≤21 righe). Per ogni giorno
     * futuro da {@code start} a {@code min(end, oggi+orizzonte)} somma la media_attesa del suo
     * giorno-della-settimana. Restituisce sia le righe aggregate per conto (per il dettaglio) sia il
     * totale giornaliero (per i bucket della timeline). Oltre l'orizzonte: niente stima (solo certo).
     */
    @SuppressWarnings("unchecked")
    private StimaRicaviCash projectStimaRicaviCash(LocalDate start, LocalDate end, LocalDate oggi) {
        Map<LocalDate, BigDecimal> giornaliera = new LinkedHashMap<>();
        LocalDate endStima = oggi.plusDays(orizzonteStimaGiorni);
        if (endStima.isAfter(end)) endStima = end;
        if (start.isAfter(endStima)) return new StimaRicaviCash(List.of(), giornaliera);

        // Baseline filtrata per soglia anti-rumore. dow Postgres: 0=domenica .. 6=sabato.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT b.conto_coge_id, b.dow, b.media_attesa, c.descrizione " +
                "FROM forecast_baseline b " +
                "JOIN piano_dei_conti_coge c ON c.id = b.conto_coge_id " +
                "WHERE b.n_giorni >= :soglia AND b.media_attesa > 0")
                .setParameter("soglia", minGiorni)
                .getResultList();
        if (rows.isEmpty()) return new StimaRicaviCash(List.of(), giornaliera);

        // conto -> [media per dow 0..6]; conto -> descrizione (LinkedHashMap mantiene ordine d'arrivo)
        Map<Integer, BigDecimal[]> mediaByConto = new LinkedHashMap<>();
        Map<Integer, String>       descrByConto = new LinkedHashMap<>();
        for (Object[] r : rows) {
            int contoId = ((Number) r[0]).intValue();
            int dow     = ((Number) r[1]).intValue();
            mediaByConto.computeIfAbsent(contoId, k -> new BigDecimal[7])[dow] = toBD(r[2]);
            descrByConto.putIfAbsent(contoId, (String) r[3]);
        }

        Map<Integer, BigDecimal> totalePerConto = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(endStima); d = d.plusDays(1)) {
            int pgDow = d.getDayOfWeek().getValue() % 7;  // Java Mon=1..Sun=7 → Postgres 1..6,0
            BigDecimal dayTot = BigDecimal.ZERO;
            for (Map.Entry<Integer, BigDecimal[]> e : mediaByConto.entrySet()) {
                BigDecimal media = e.getValue()[pgDow];
                if (media == null) continue;
                dayTot = dayTot.add(media);
                totalePerConto.merge(e.getKey(), media, BigDecimal::add);
            }
            if (dayTot.signum() > 0) giornaliera.put(d, dayTot);
        }

        // Una riga aggregata per conto, datata a start (il dettaglio è una sintesi; la distribuzione
        // giorno-per-giorno vive nella timeline). affidabilita=STIMATO la tiene fuori dai subtotali certi.
        List<ForecastingDettaglioDTO> righe = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> e : totalePerConto.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            String desc = "Stima " + descrByConto.get(e.getKey())
                    + " — media ultime " + finestraSettimane + " sett.";
            righe.add(new ForecastingDettaglioDTO(
                    start, "MOVIMENTO", desc,
                    e.getValue().setScale(2, java.math.RoundingMode.HALF_UP),
                    BigDecimal.ZERO, "ENTRAMBE", "STIMATO"));
        }
        return new StimaRicaviCash(righe, giornaliera);
    }

    // ── P7: stima dei costi ricorrenti ────────────────────────────────────────

    private record StimaCosti(List<ForecastingDettaglioDTO> righe,
                              Map<LocalDate, BigDecimal> perData,
                              String nota) {
        static StimaCosti spenta(String nota) { return new StimaCosti(List.of(), Map.of(), nota); }
    }

    /**
     * Costi ricorrenti stimati: media MENSILE per conto sugli ultimi {@code finestra-mesi} mesi
     * interi, proiettata come una riga per conto per ogni mese futuro dell'orizzonte.
     *
     * <p><b>Perché mensile e non per giorno-della-settimana</b> (come la stima ricavi): i ricavi
     * cash sono giornalieri, i costi no — sono fatture e canoni, mensili e lumpy. Applicare ai
     * costi la media per {@code dow} dei ricavi copierebbe la forma sbagliata.
     *
     * <p><b>Quando matura:</b> ultimo giorno del mese, la stessa convenzione già usata dagli
     * ammortamenti — mese intero, nessun pro-rata sui giorni. Conseguenza voluta: il mese in corso
     * entra tutto o niente a seconda che la sua fine cada nell'orizzonte, invece di essere spalmato
     * su una frazione di mese che nessun canone rispetta.
     *
     * <p><b>Gate di sufficienza dati, fail-closed.</b> Non è un ripiego, è parte della feature: con
     * un solo mese importato una media è la copia di quel mese. Servono tutte e tre le condizioni, e
     * quando non ci sono la stima non esce e la nota dice perché.
     *
     * <p><b>Data usata: {@code data_competenza}</b> — è un costo di competenza, non un pagamento.
     * Diverso da come {@code ForecastBaselineService} costruisce la baseline ricavi (usa
     * {@code data_movimento}): difetto noto e misurato, non replicato qui. Vedi «Fuori scopo» nella
     * spec.
     */
    @SuppressWarnings("unchecked")
    private StimaCosti projectStimaCosti(LocalDate start, LocalDate end, LocalDate oggi,
                                         boolean datiIncompleti) {
        // Finestra: gli ultimi `finestraMesiCosti` mesi INTERI, mai sotto il go-live (R7.7).
        YearMonth ultimoIntero = YearMonth.from(oggi).minusMonths(1);
        YearMonth primo        = ultimoIntero.minusMonths(finestraMesiCosti - 1L);
        YearMonth goLiveYm     = YearMonth.from(Perimetro.GO_LIVE);
        if (primo.isBefore(goLiveYm)) primo = goLiveYm;
        // Gate 1 — quanti mesi interi hanno almeno un costo proiettabile. Si valuta anche quando la
        // finestra è degenere (0 mesi disponibili sopra il go-live), così la nota dice sempre il numero.
        int mesiConDati = 0;
        LocalDate finestraStart = primo.atDay(1);
        LocalDate finestraEnd   = ultimoIntero.atEndOfMonth();
        int mesiFinestra = (int) ChronoUnit.MONTHS.between(primo, ultimoIntero) + 1;
        if (!primo.isAfter(ultimoIntero)) {
            mesiConDati = ((Number) em.createNativeQuery(
                    "SELECT COUNT(DISTINCT date_trunc('month', m.data_competenza)) " +
                    "FROM movimenti m JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                    "WHERE " + FILTRO_COSTI_PROIETTABILI +
                    " AND m.data_competenza BETWEEN :from AND :to")
                    .setParameter("from", finestraStart)
                    .setParameter("to", finestraEnd)
                    .getSingleResult()).intValue();
        }

        // Il gate è fail-closed e dice TUTTE le ragioni per cui non passa, non solo la prima:
        // «i dati non sono aggiornati» l'utente lo legge già nel banner di P6 — la ragione che gli
        // serve qui è quanti mesi mancano.
        List<String> motivi = new ArrayList<>();
        if (mesiConDati < minMesiCosti) {
            motivi.add("servono " + minMesiCosti + (minMesiCosti == 1 ? " mese intero" : " mesi interi")
                     + " di dati, ce " + (mesiConDati == 1 ? "n'è 1" : "ne sono " + mesiConDati));
        }
        // Gate 3 — su dati fermi non si stima: si stimerebbe sul buco, non sull'andamento.
        if (datiIncompleti) motivi.add("i dati di cassa non sono aggiornati");
        if (!motivi.isEmpty()) {
            return StimaCosti.spenta("Stima costi non disponibile: " + String.join("; ", motivi) + ".");
        }

        // Gate 2 — soglia PER CONTO, simmetrica a `min-giorni` sui ricavi: un conto visto una volta
        // sola non ha una media, ha un episodio.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.conto_coge_id, pc.descrizione, " +
                "       SUM(COALESCE(m.importo_imponibile, m.importo_lordo)) AS totale " +
                "FROM movimenti m JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                "WHERE " + FILTRO_COSTI_PROIETTABILI +
                " AND m.data_competenza BETWEEN :from AND :to " +
                "GROUP BY m.conto_coge_id, pc.descrizione " +
                "HAVING COUNT(DISTINCT date_trunc('month', m.data_competenza)) >= :minMesi " +
                "   AND SUM(COALESCE(m.importo_imponibile, m.importo_lordo)) > 0 " +
                "ORDER BY m.conto_coge_id")
                .setParameter("from", finestraStart)
                .setParameter("to", finestraEnd)
                .setParameter("minMesi", minMesiCosti)
                .getResultList();
        if (rows.isEmpty()) {
            return StimaCosti.spenta("Stima costi non disponibile: nessun conto di costo ha almeno "
                    + minMesiCosti + " mesi con occorrenza nella finestra.");
        }

        // Mesi futuri la cui FINE cade nell'orizzonte: stessa convenzione degli ammortamenti.
        List<LocalDate> mesiProiettati = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(start); !ym.isAfter(YearMonth.from(end)); ym = ym.plusMonths(1)) {
            LocalDate fineMese = ym.atEndOfMonth();
            if (!fineMese.isBefore(start) && !fineMese.isAfter(end)) mesiProiettati.add(fineMese);
        }
        if (mesiProiettati.isEmpty()) return new StimaCosti(List.of(), Map.of(), null);

        List<ForecastingDettaglioDTO> righe = new ArrayList<>();
        Map<LocalDate, BigDecimal>    perData = new LinkedHashMap<>();
        for (Object[] r : rows) {
            BigDecimal media = toBD(r[2]).divide(BigDecimal.valueOf(mesiFinestra), 2, RoundingMode.HALF_UP);
            if (media.signum() <= 0) continue;
            String desc = "Stima " + r[1] + " — media mensile ultimi " + mesiFinestra + " mesi";
            for (LocalDate fineMese : mesiProiettati) {
                righe.add(new ForecastingDettaglioDTO(
                        fineMese, "MOVIMENTO", desc, BigDecimal.ZERO, media, "ENTRAMBE", "STIMATO"));
                perData.merge(fineMese, media, BigDecimal::add);
            }
        }
        return new StimaCosti(righe, perData, null);
    }

    /**
     * Cosa può entrare nella media dei costi. Ogni esclusione evita un doppio conteggio o una bugia:
     * <ul>
     *   <li>{@code pc.tipo <> 'COSTO'} o {@code is_capex} — convenzione V38/V39: un investimento non
     *       è un costo operativo;</li>
     *   <li>conti transitori ({@link ReportingService#TRANSITORI}, la stessa costante che il P&amp;L
     *       usa per misurarli) — proiettare «da classificare» come costo perpetuo è inventare;</li>
     *   <li>conti agganciati a un piano ricorrente ATTIVO — sono già nel CERTO come rate, stimarli
     *       li conterebbe due volte. È il doppio conteggio più probabile di tutta la feature;</li>
     *   <li>{@code evento_id} — i costi diretti di un evento seguono l'evento, non una media;</li>
     *   <li>{@code stato = 'ANNULLATO'} — ovvio, ma va scritto.</li>
     * </ul>
     */
    private static final String FILTRO_COSTI_PROIETTABILI =
            " m.stato <> 'ANNULLATO' AND m.tipo = 'USCITA' " +
            " AND pc.tipo = 'COSTO' AND NOT pc.is_capex " +
            " AND NOT " + ReportingService.TRANSITORI +
            " AND m.evento_id IS NULL " +
            " AND m.conto_coge_id NOT IN (" +
            "     SELECT conto_coge_id FROM recurring_expense_plan " +
            "      WHERE stato = 'ATTIVO' AND conto_coge_id IS NOT NULL " +
            "      UNION " +
            "     SELECT conto_coge_interessi_id FROM recurring_expense_plan " +
            "      WHERE stato = 'ATTIVO' AND conto_coge_interessi_id IS NOT NULL) ";

    // ── Timeline aggregata ────────────────────────────────────────────────────

    private List<ForecastingTimelineDTO> buildTimeline(
            List<ForecastingDettaglioDTO> items,
            LocalDate start, LocalDate end,
            BigDecimal saldoPartenza,
            boolean settimanale,
            Map<LocalDate, BigDecimal> stimaGiornaliera,
            Map<LocalDate, BigDecimal> stimaCostiPerData) {

        // Aggrega items per bucket
        Map<String, BigDecimal[]> bucketMap = new LinkedHashMap<>();
        Map<String, LocalDate[]>  bucketBounds = new LinkedHashMap<>();
        Map<String, BigDecimal>   stimaBucket = new LinkedHashMap<>();
        Map<String, BigDecimal>   stimaCostiBucket = new LinkedHashMap<>();

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

        // Stima ricavi cash: ogni giorno futuro cade già in [start, end] → bucket diretto, nessun
        // fallback al primo bucket. Tiene la stima separata dal certo (saldo progressivo = solo certo).
        for (Map.Entry<LocalDate, BigDecimal> s : stimaGiornaliera.entrySet()) {
            LocalDate d = s.getKey();
            if (d.isBefore(start) || d.isAfter(end)) continue;
            String key = settimanale ? bucketKeyWeek(d) : bucketKeyMonth(d);
            if (!bucketMap.containsKey(key)) continue;
            stimaBucket.merge(key, s.getValue(), BigDecimal::add);
        }

        // Costi stimati (P7): stesso trattamento, colonna separata. Non toccano il saldo progressivo.
        for (Map.Entry<LocalDate, BigDecimal> c : stimaCostiPerData.entrySet()) {
            LocalDate d = c.getKey();
            if (d.isBefore(start) || d.isAfter(end)) continue;
            String key = settimanale ? bucketKeyWeek(d) : bucketKeyMonth(d);
            if (!bucketMap.containsKey(key)) continue;
            stimaCostiBucket.merge(key, c.getValue(), BigDecimal::add);
        }

        // Costruisce la lista ordinata con saldo progressivo (sul solo certo)
        List<ForecastingTimelineDTO> result = new ArrayList<>();
        BigDecimal saldo = saldoPartenza;
        for (Map.Entry<String, BigDecimal[]> e : bucketMap.entrySet()) {
            String key = e.getKey();
            BigDecimal entr = e.getValue()[0];
            BigDecimal usc  = e.getValue()[1];
            BigDecimal ebitda = entr.subtract(usc);
            saldo = saldo.add(entr).subtract(usc);
            LocalDate[] bounds = bucketBounds.get(key);
            BigDecimal stimata = stimaBucket.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal stimateUsc = stimaCostiBucket.getOrDefault(key, BigDecimal.ZERO);
            result.add(new ForecastingTimelineDTO(
                    key, bounds[0], bounds[1], entr, usc, ebitda, saldo, stimata, stimateUsc));
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
